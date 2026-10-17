package com.shopbilling.service;

import com.shopbilling.db.CustomerLedgerRepository;
import com.shopbilling.db.CustomerRepository;
import com.shopbilling.db.PaymentRepository;
import com.shopbilling.db.ProductRepository;
import com.shopbilling.db.ProductVariantRepository;
import com.shopbilling.db.SaleRepository;
import com.shopbilling.db.SettingsRepository;
import com.shopbilling.db.TransactionHelper;
import com.shopbilling.db.UnitRepository;
import com.shopbilling.model.Customer;
import com.shopbilling.model.FinancialYear;
import com.shopbilling.model.Money;
import com.shopbilling.model.PaymentInput;
import com.shopbilling.model.PaymentRecord;
import com.shopbilling.model.PaymentSplit;
import com.shopbilling.model.Product;
import com.shopbilling.model.ProductVariant;
import com.shopbilling.model.Sale;
import com.shopbilling.model.SaleItem;
import com.shopbilling.model.SaleItemInput;
import com.shopbilling.model.SaleMode;
import com.shopbilling.model.StockMovementType;
import com.shopbilling.model.Unit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Service managing Register Sale operations (PRD Section 6, 7.3, FR-1, FR-8 - FR-14).
 * Creates registered sales that deduct stock and track payments/turnover,
 * but do NOT assign a bill number or increment the bill counter.
 * Pure service: strictly contains NO JavaFX imports.
 */
public class RegisterSaleService {

    private static final Logger logger = LoggerFactory.getLogger(RegisterSaleService.class);

    private final TransactionHelper transactionHelper;
    private final SaleRepository saleRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentValidationService paymentValidationService;
    private final StockService stockService;
    private final CustomerLedgerRepository customerLedgerRepository;
    private final CustomerRepository customerRepository;
    private final ProductVariantRepository variantRepository;
    private final ProductRepository productRepository;
    private final UnitRepository unitRepository;
    private final SettingsRepository settingsRepository;
    private final IstClock clock;

    public RegisterSaleService(TransactionHelper transactionHelper,
                               SaleRepository saleRepository,
                               PaymentRepository paymentRepository,
                               PaymentValidationService paymentValidationService,
                               StockService stockService,
                               CustomerLedgerRepository customerLedgerRepository,
                               CustomerRepository customerRepository,
                               ProductVariantRepository variantRepository,
                               ProductRepository productRepository,
                               UnitRepository unitRepository,
                               SettingsRepository settingsRepository,
                               IstClock clock) {
        this.transactionHelper = Objects.requireNonNull(transactionHelper, "TransactionHelper cannot be null");
        this.saleRepository = Objects.requireNonNull(saleRepository, "SaleRepository cannot be null");
        this.paymentRepository = Objects.requireNonNull(paymentRepository, "PaymentRepository cannot be null");
        this.paymentValidationService = Objects.requireNonNull(paymentValidationService, "PaymentValidationService cannot be null");
        this.stockService = Objects.requireNonNull(stockService, "StockService cannot be null");
        this.customerLedgerRepository = Objects.requireNonNull(customerLedgerRepository, "CustomerLedgerRepository cannot be null");
        this.customerRepository = Objects.requireNonNull(customerRepository, "CustomerRepository cannot be null");
        this.variantRepository = Objects.requireNonNull(variantRepository, "ProductVariantRepository cannot be null");
        this.productRepository = Objects.requireNonNull(productRepository, "ProductRepository cannot be null");
        this.unitRepository = Objects.requireNonNull(unitRepository, "UnitRepository cannot be null");
        this.settingsRepository = Objects.requireNonNull(settingsRepository, "SettingsRepository cannot be null");
        this.clock = Objects.requireNonNull(clock, "IstClock cannot be null");
    }

    /**
     * Registers an unbilled sale atomically.
     * Deducts stock, records payments, updates customer ledger dues if credit is used,
     * but does NOT assign a bill number.
     *
     * @param customerId Optional customer ID (required if credit is used)
     * @param saleDate Optional sale date, defaults to current date in IST
     * @param items Non-empty list of items
     * @param overallDiscount Optional discount applied to total (default 0)
     * @param payments Payment split matching the total sale amount
     * @param userId User registering the sale
     * @return Complete persisted Sale domain object
     */
    public Sale registerSale(Long customerId,
                             LocalDate saleDate,
                             List<SaleItemInput> items,
                             Money overallDiscount,
                             PaymentSplit payments,
                             long userId) {
        if (items == null || items.isEmpty()) {
            throw new ValidationException("Cannot register a sale with an empty items list");
        }
        Objects.requireNonNull(payments, "Payments cannot be null");

        final LocalDate effectiveDate = saleDate != null ? saleDate : clock.today();
        final Money effectiveDiscount = overallDiscount != null ? overallDiscount : Money.ZERO;
        if (effectiveDiscount.isNegative()) {
            throw new ValidationException("Overall discount cannot be negative: " + effectiveDiscount);
        }

        return transactionHelper.execute(conn -> {
            // 1. Verify user exists and check staff permissions
            String userRole = getUserRole(conn, userId);
            if ("STAFF".equalsIgnoreCase(userRole)) {
                boolean staffCanRegister = Boolean.parseBoolean(
                        settingsRepository.getSettingOrDefault(conn, "STAFF_CAN_REGISTER_SALE", "true")
                );
                if (!staffCanRegister) {
                    throw new ValidationException("Counter staff members are not permitted to register unbilled sales per shop settings");
                }
            }

            // 2. Validate customer exists if provided
            if (customerId != null) {
                customerRepository.findById(conn, customerId)
                        .orElseThrow(() -> new EntityNotFoundException("Customer id=" + customerId + " not found"));
            }

            // 3. Validate items, variants, and units (enforce no fractions for non-decimal units)
            Money subtotal = Money.ZERO;
            Money totalLineDiscounts = Money.ZERO;
            Money subtotalBeforeLineDiscounts = Money.ZERO;

            for (SaleItemInput item : items) {
                ProductVariant variant = variantRepository.findById(conn, item.variantId())
                        .orElseThrow(() -> new EntityNotFoundException("ProductVariant id=" + item.variantId() + " not found"));

                Product product = productRepository.findById(conn, variant.productId())
                        .orElseThrow(() -> new EntityNotFoundException("Product id=" + variant.productId() + " not found"));

                Unit unit = unitRepository.findByCode(conn, product.unitCode())
                        .orElseThrow(() -> new EntityNotFoundException("Unit " + product.unitCode() + " not found"));

                item.quantity().validateForUnit(unit);

                Money lineSubtotal = item.calculateSubtotal();
                Money lineTotal = item.calculateLineTotal();
                subtotal = subtotal.plus(lineTotal);
                totalLineDiscounts = totalLineDiscounts.plus(item.lineDiscount());
                subtotalBeforeLineDiscounts = subtotalBeforeLineDiscounts.plus(lineSubtotal);
            }

            if (effectiveDiscount.isGreaterThan(subtotal)) {
                throw new ValidationException(String.format(
                        "Overall discount (%s) cannot exceed subtotal (%s)",
                        effectiveDiscount, subtotal
                ));
            }

            Money totalAmount = subtotal.minus(effectiveDiscount);

            // 4. Validate staff discount limit if staff user
            if ("STAFF".equalsIgnoreCase(userRole) && subtotalBeforeLineDiscounts.isPositive()) {
                Money totalDiscount = totalLineDiscounts.plus(effectiveDiscount);
                double requestedPercent = (totalDiscount.getPaise() * 100.0) / subtotalBeforeLineDiscounts.getPaise();
                double maxPercent = Double.parseDouble(
                        settingsRepository.getSettingOrDefault(conn, "STAFF_MAX_DISCOUNT_PERCENT", "5.00")
                );
                if (requestedPercent > (maxPercent + 0.0001)) {
                    throw new ValidationException(String.format(
                            "Staff discount limit exceeded: requested %.2f%%, maximum allowed is %.2f%%",
                            requestedPercent, maxPercent
                    ));
                }
            }

            // 5. Validate payments
            paymentValidationService.validatePayments(conn, payments, totalAmount, customerId);

            // 6. Insert Sale header record (sale_mode = REGISTERED, bill_number = null)
            String financialYear = FinancialYear.ofDate(effectiveDate).getLabel();
            Sale saleToInsert = new Sale(
                    null,
                    SaleMode.REGISTERED,
                    financialYear,
                    null, // bill_number must be null for registered sales
                    null, // bill_sequence must be null for registered sales
                    customerId,
                    effectiveDate,
                    null,
                    subtotal,
                    effectiveDiscount,
                    totalAmount,
                    userId,
                    List.of(),
                    List.of()
            );
            Sale savedSale = saleRepository.insertSale(conn, saleToInsert);
            long saleId = savedSale.id();

            // 7. Insert line items and deduct stock
            List<SaleItem> savedItems = new ArrayList<>();
            for (SaleItemInput input : items) {
                SaleItem itemToInsert = new SaleItem(
                        null,
                        saleId,
                        input.variantId(),
                        input.quantity(),
                        input.unitPrice(),
                        input.lineDiscount(),
                        input.calculateLineTotal()
                );
                SaleItem savedItem = saleRepository.insertSaleItem(conn, itemToInsert);
                savedItems.add(savedItem);

                // Deduct stock via StockMovementType.SALE with negative quantity
                stockService.recordMovementInTransaction(
                        conn,
                        input.variantId(),
                        StockMovementType.SALE,
                        input.quantity().negate(),
                        "SALE",
                        saleId,
                        "Registered sale #" + saleId,
                        userId
                );
            }

            // 8. Insert split payments
            List<PaymentRecord> savedPayments = new ArrayList<>();
            for (PaymentInput paymentInput : payments.getPayments()) {
                PaymentRecord pr = paymentRepository.insertPayment(conn, saleId, paymentInput);
                savedPayments.add(pr);
            }

            // 9. If credit payment was made, append to customer ledger entries
            if (payments.hasCredit()) {
                customerLedgerRepository.insertEntry(
                        conn,
                        customerId,
                        "CREDIT_SALE",
                        payments.creditTotal().getPaise(),
                        "SALE",
                        saleId,
                        effectiveDate.toString(),
                        "Registered credit sale #" + saleId,
                        userId
                );
            }

            logger.info("Registered sale recorded successfully: saleId={} fy='{}' items={} total={} custId={}",
                    saleId, financialYear, savedItems.size(), totalAmount, customerId);

            return new Sale(
                    saleId,
                    SaleMode.REGISTERED,
                    financialYear,
                    null,
                    null,
                    customerId,
                    effectiveDate,
                    savedSale.createdAt(),
                    subtotal,
                    effectiveDiscount,
                    totalAmount,
                    userId,
                    savedItems,
                    savedPayments
            );
        });
    }

    /**
     * Retrieves a full Sale by ID, including its line items and payments.
     */
    public Optional<Sale> getSaleById(long saleId) {
        return transactionHelper.execute(conn -> {
            Optional<Sale> optSale = saleRepository.findById(conn, saleId);
            if (optSale.isEmpty()) {
                return Optional.empty();
            }
            Sale s = optSale.get();
            List<SaleItem> items = saleRepository.findItemsBySaleId(conn, saleId);
            List<PaymentRecord> payments = paymentRepository.findBySaleId(conn, saleId);
            return Optional.of(new Sale(
                    s.id(),
                    s.saleMode(),
                    s.financialYear(),
                    s.billNumber(),
                    s.billSequence(),
                    s.customerId(),
                    s.saleDate(),
                    s.createdAt(),
                    s.subtotal(),
                    s.discount(),
                    s.totalAmount(),
                    s.createdByUserId(),
                    items,
                    payments
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
