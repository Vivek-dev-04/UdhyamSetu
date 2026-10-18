package com.shopbilling.service;

import com.shopbilling.db.CreditNoteCounterRepository;
import com.shopbilling.db.CustomerLedgerRepository;
import com.shopbilling.db.CustomerRepository;
import com.shopbilling.db.ReturnRepository;
import com.shopbilling.db.SaleRepository;
import com.shopbilling.db.SettingsRepository;
import com.shopbilling.db.TransactionHelper;
import com.shopbilling.model.CreditNoteNumber;
import com.shopbilling.model.FinancialYear;
import com.shopbilling.model.Money;
import com.shopbilling.model.PaymentMode;
import com.shopbilling.model.Quantity;
import com.shopbilling.model.ReturnCondition;
import com.shopbilling.model.ReturnItemInput;
import com.shopbilling.model.ReturnItemRecord;
import com.shopbilling.model.ReturnRecord;
import com.shopbilling.model.Sale;
import com.shopbilling.model.SaleItem;
import com.shopbilling.model.StockMovementType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Service managing customer returns and credit notes (PRD Section 7.5, FR-20 - FR-25).
 * - Creates linked return records without ever modifying or deleting the original sale.
 * - Adds SELLABLE items back to stock; keeps DAMAGED items out of active stock.
 * - Refunds via cash/UPI or customer ledger credit (reducing dues).
 * - Assigns gap-free Credit Note numbers for billed sales.
 * - Enforces staff return value approval thresholds.
 * Pure service: strictly contains NO JavaFX imports.
 */
public class ReturnService {

    private static final Logger logger = LoggerFactory.getLogger(ReturnService.class);

    private final TransactionHelper transactionHelper;
    private final ReturnRepository returnRepository;
    private final CreditNoteCounterRepository creditNoteCounterRepository;
    private final SaleRepository saleRepository;
    private final StockService stockService;
    private final CustomerLedgerRepository customerLedgerRepository;
    private final CustomerRepository customerRepository;
    private final SettingsRepository settingsRepository;
    private final IstClock clock;

    public ReturnService(TransactionHelper transactionHelper,
                         ReturnRepository returnRepository,
                         CreditNoteCounterRepository creditNoteCounterRepository,
                         SaleRepository saleRepository,
                         StockService stockService,
                         CustomerLedgerRepository customerLedgerRepository,
                         CustomerRepository customerRepository,
                         SettingsRepository settingsRepository,
                         IstClock clock) {
        this.transactionHelper = Objects.requireNonNull(transactionHelper, "TransactionHelper cannot be null");
        this.returnRepository = Objects.requireNonNull(returnRepository, "ReturnRepository cannot be null");
        this.creditNoteCounterRepository = Objects.requireNonNull(creditNoteCounterRepository, "CreditNoteCounterRepository cannot be null");
        this.saleRepository = Objects.requireNonNull(saleRepository, "SaleRepository cannot be null");
        this.stockService = Objects.requireNonNull(stockService, "StockService cannot be null");
        this.customerLedgerRepository = Objects.requireNonNull(customerLedgerRepository, "CustomerLedgerRepository cannot be null");
        this.customerRepository = Objects.requireNonNull(customerRepository, "CustomerRepository cannot be null");
        this.settingsRepository = Objects.requireNonNull(settingsRepository, "SettingsRepository cannot be null");
        this.clock = Objects.requireNonNull(clock, "IstClock cannot be null");
    }

    /**
     * Processes a customer return atomically.
     *
     * @param saleId ID of the sale being returned against
     * @param returnDate Optional date of return, defaults to current date in IST
     * @param items List of item return requests
     * @param refundMode Mode of refund: CASH, UPI, or CREDIT
     * @param notes Optional notes/reason
     * @param userId User processing the return
     * @return Persisted ReturnRecord domain object with items and credit note number (if billed)
     */
    public ReturnRecord processReturn(long saleId,
                                      LocalDate returnDate,
                                      List<ReturnItemInput> items,
                                      PaymentMode refundMode,
                                      String notes,
                                      long userId) {
        if (items == null || items.isEmpty()) {
            throw new ValidationException("Return must contain at least one item");
        }
        Objects.requireNonNull(refundMode, "RefundMode cannot be null");

        final LocalDate effectiveDate = returnDate != null ? returnDate : clock.today();

        return transactionHelper.execute(conn -> {
            // 1. Verify user exists and check role
            String userRole = getUserRole(conn, userId);

            // 2. Fetch sale and line items
            Sale sale = saleRepository.findById(conn, saleId)
                    .orElseThrow(() -> new EntityNotFoundException("Sale id=" + saleId + " not found"));

            List<SaleItem> saleItems = saleRepository.findItemsBySaleId(conn, saleId);
            Map<Long, SaleItem> saleItemMap = saleItems.stream()
                    .collect(Collectors.toMap(SaleItem::id, Function.identity()));

            // 3. Validate line return quantities & compute item refunds
            Money totalRefund = Money.ZERO;
            record ValidatedItem(ReturnItemInput input, SaleItem saleItem, Money refund) {}
            List<ValidatedItem> validatedItems = new ArrayList<>();

            for (ReturnItemInput input : items) {
                SaleItem saleItem = saleItemMap.get(input.saleItemId());
                if (saleItem == null) {
                    throw new ValidationException("Sale item id=" + input.saleItemId() + " does not belong to sale id=" + saleId);
                }

                Quantity alreadyReturned = returnRepository.getTotalReturnedQuantityForSaleItem(conn, input.saleItemId());
                Quantity availableToReturn = saleItem.quantity().minus(alreadyReturned);

                if (input.quantity().compareTo(availableToReturn) > 0) {
                    throw new ValidationException(String.format(
                            "Cannot return %s units of sale item id=%d. Already returned: %s, Max available: %s",
                            input.quantity().toDisplayString(),
                            input.saleItemId(),
                            alreadyReturned.toDisplayString(),
                            availableToReturn.toDisplayString()
                    ));
                }

                // Proportional refund calculation based on line total
                long itemRefundPaise = (saleItem.lineTotal().getPaise() * input.quantity().getScaledValue()) / saleItem.quantity().getScaledValue();
                Money itemRefund = Money.ofPaise(itemRefundPaise);
                totalRefund = totalRefund.plus(itemRefund);

                validatedItems.add(new ValidatedItem(input, saleItem, itemRefund));
            }

            // 4. Validate staff return approval threshold
            if ("STAFF".equalsIgnoreCase(userRole)) {
                long maxStaffLimitPaise = Long.parseLong(
                        settingsRepository.getSettingOrDefault(conn, "STAFF_RETURN_APPROVAL_LIMIT_PAISE", "50000")
                );
                if (totalRefund.getPaise() > maxStaffLimitPaise) {
                    throw new ValidationException(String.format(
                            "Staff return limit exceeded: refund amount ₹%s exceeds maximum staff approval limit of ₹%s. Shop owner approval is required.",
                            totalRefund.toRupeesString(),
                            Money.ofPaise(maxStaffLimitPaise).toRupeesString()
                    ));
                }
            }

            // 5. If refund mode is CREDIT, ensure customer exists
            if (refundMode == PaymentMode.CREDIT) {
                if (sale.customerId() == null) {
                    throw new ValidationException("Cannot issue refund as CREDIT for a walk-in sale without an identified customer");
                }
            }

            // 6. Generate Credit Note number if original sale was BILLED
            String creditNoteNumber = null;
            FinancialYear fy = FinancialYear.ofDate(effectiveDate);
            if (sale.isBilled()) {
                CreditNoteNumber cn = creditNoteCounterRepository.nextCreditNoteNumber(conn, fy);
                creditNoteNumber = cn.formattedNumber();
            }

            // 7. Insert Return header record
            ReturnRecord headerToInsert = new ReturnRecord(
                    null,
                    saleId,
                    creditNoteNumber,
                    fy.getLabel(),
                    effectiveDate,
                    refundMode,
                    totalRefund,
                    notes,
                    null,
                    userId,
                    List.of()
            );
            ReturnRecord savedHeader = returnRepository.insertReturn(conn, headerToInsert);
            long returnId = savedHeader.id();

            // 8. Insert return items and handle stock
            List<ReturnItemRecord> savedItems = new ArrayList<>();
            for (ValidatedItem val : validatedItems) {
                ReturnItemRecord itemToInsert = new ReturnItemRecord(
                        null,
                        returnId,
                        val.input().saleItemId(),
                        val.saleItem().variantId(),
                        val.input().quantity(),
                        val.input().condition(),
                        val.refund(),
                        val.input().reason()
                );
                ReturnItemRecord savedItem = returnRepository.insertReturnItem(conn, itemToInsert);
                savedItems.add(savedItem);

                // Stock update: SELLABLE adds back to stock; DAMAGED items remain in damaged bucket and do not increase sellable stock
                if (val.input().condition() == ReturnCondition.SELLABLE) {
                    stockService.recordMovementInTransaction(
                            conn,
                            val.saleItem().variantId(),
                            StockMovementType.RETURN_IN,
                            val.input().quantity(), // positive quantity increments stock
                            "RETURN",
                            returnId,
                            "Customer return #" + returnId + " (Sellable)",
                            userId
                    );
                } else {
                    logger.debug("Return item id={} condition=DAMAGED (tracked in return_items, not added to active inventory)",
                            savedItem.id());
                }
            }

            // 9. If refund mode is CREDIT, record negative customer ledger entry (reduces customer dues)
            if (refundMode == PaymentMode.CREDIT) {
                customerLedgerRepository.insertEntry(
                        conn,
                        sale.customerId(),
                        "RETURN_REFUND",
                        -totalRefund.getPaise(), // negative amount reduces dues
                        "RETURN",
                        returnId,
                        effectiveDate.toString(),
                        creditNoteNumber != null ? ("Credit Note " + creditNoteNumber) : ("Return for sale #" + saleId),
                        userId
                );
            }

            logger.info("Processed return id={} for saleId={} totalRefund={} refundMode={} creditNote={}",
                    returnId, saleId, totalRefund.toRupeesString(), refundMode, creditNoteNumber);

            return new ReturnRecord(
                    returnId,
                    saleId,
                    creditNoteNumber,
                    fy.getLabel(),
                    effectiveDate,
                    refundMode,
                    totalRefund,
                    notes,
                    savedHeader.createdAt(),
                    userId,
                    savedItems
            );
        });
    }

    /**
     * Retrieves all returns for a given sale.
     */
    public List<ReturnRecord> getReturnsForSale(long saleId) {
        return transactionHelper.execute(conn -> {
            List<ReturnRecord> returns = returnRepository.findBySaleId(conn, saleId);
            List<ReturnRecord> result = new ArrayList<>();
            for (ReturnRecord r : returns) {
                List<ReturnItemRecord> items = returnRepository.findItemsByReturnId(conn, r.id());
                result.add(new ReturnRecord(
                        r.id(),
                        r.saleId(),
                        r.creditNoteNumber(),
                        r.financialYear(),
                        r.returnDate(),
                        r.refundMode(),
                        r.totalRefund(),
                        r.notes(),
                        r.createdAt(),
                        r.createdByUserId(),
                        items
                ));
            }
            return result;
        });
    }

    /**
     * Retrieves a return by its ID with its line items.
     */
    public Optional<ReturnRecord> getReturnById(long returnId) {
        return transactionHelper.execute(conn -> {
            Optional<ReturnRecord> opt = returnRepository.findById(conn, returnId);
            if (opt.isEmpty()) {
                return Optional.empty();
            }
            ReturnRecord r = opt.get();
            List<ReturnItemRecord> items = returnRepository.findItemsByReturnId(conn, returnId);
            return Optional.of(new ReturnRecord(
                    r.id(),
                    r.saleId(),
                    r.creditNoteNumber(),
                    r.financialYear(),
                    r.returnDate(),
                    r.refundMode(),
                    r.totalRefund(),
                    r.notes(),
                    r.createdAt(),
                    r.createdByUserId(),
                    items
            ));
        });
    }

    private String getUserRole(Connection conn, long userId) throws SQLException {
        String sql = "SELECT role FROM users WHERE id = ?;";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("role");
                }
            }
        }
        throw new EntityNotFoundException("User id=" + userId + " not found");
    }
}
