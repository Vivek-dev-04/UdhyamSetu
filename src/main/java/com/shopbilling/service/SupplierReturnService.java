package com.shopbilling.service;

import com.shopbilling.db.ProductVariantRepository;
import com.shopbilling.db.SupplierLedgerRepository;
import com.shopbilling.db.SupplierRepository;
import com.shopbilling.db.TransactionHelper;
import com.shopbilling.db.UserRepository;
import com.shopbilling.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Service managing stock returns to distributors/suppliers and debit note generation
 * (PRD Section 7.4, 7.6, 7.10; FR-15, FR-29, FR-44).
 * Pure service: strictly contains NO JavaFX imports.
 */
public class SupplierReturnService {

    private static final Logger logger = LoggerFactory.getLogger(SupplierReturnService.class);

    private final TransactionHelper transactionHelper;
    private final SupplierRepository supplierRepository;
    private final SupplierLedgerRepository supplierLedgerRepository;
    private final ProductVariantRepository variantRepository;
    private final StockService stockService;
    private final UserRepository userRepository;
    private final AuditLogService auditLogService;
    private final IstClock clock;

    public SupplierReturnService(TransactionHelper transactionHelper,
                                 SupplierRepository supplierRepository,
                                 SupplierLedgerRepository supplierLedgerRepository,
                                 ProductVariantRepository variantRepository,
                                 StockService stockService,
                                 UserRepository userRepository,
                                 AuditLogService auditLogService,
                                 IstClock clock) {
        this.transactionHelper = Objects.requireNonNull(transactionHelper, "TransactionHelper cannot be null");
        this.supplierRepository = Objects.requireNonNull(supplierRepository, "SupplierRepository cannot be null");
        this.supplierLedgerRepository = Objects.requireNonNull(supplierLedgerRepository, "SupplierLedgerRepository cannot be null");
        this.variantRepository = Objects.requireNonNull(variantRepository, "ProductVariantRepository cannot be null");
        this.stockService = Objects.requireNonNull(stockService, "StockService cannot be null");
        this.userRepository = Objects.requireNonNull(userRepository, "UserRepository cannot be null");
        this.auditLogService = Objects.requireNonNull(auditLogService, "AuditLogService cannot be null");
        this.clock = Objects.requireNonNull(clock, "IstClock cannot be null");
    }

    /**
     * Processes a stock return to a distributor, deducting physical stock, creating a supplier debit
     * ledger entry, and generating an audit log (FR-15, FR-29, FR-44).
     *
     * @param requestingUserId The owner authorizing the distributor return.
     * @param supplierId The supplier receiving the returned goods.
     * @param returnDate The effective return date (defaults to today in IST).
     * @param items List of returned variants, quantities, unit cost credits, and reasons.
     * @param notes Optional notes.
     * @return Completed SupplierReturnResult with debit note details.
     */
    public SupplierReturnResult processSupplierReturn(long requestingUserId,
                                                      long supplierId,
                                                      LocalDate returnDate,
                                                      List<SupplierReturnItemInput> items,
                                                      String notes) {
        if (items == null || items.isEmpty()) {
            throw new ValidationException("Cannot process supplier return with empty items list");
        }

        final LocalDate effectiveDate = returnDate != null ? returnDate : clock.today();

        return transactionHelper.execute(conn -> {
            // 1. Verify owner authorization
            verifyOwner(conn, requestingUserId);

            // 2. Verify supplier exists
            Supplier supplier = supplierRepository.findById(conn, supplierId)
                    .orElseThrow(() -> new EntityNotFoundException("Supplier id=" + supplierId + " not found"));

            // 3. Validate variants and compute total debit amount
            Money totalDebit = Money.ZERO;
            List<SupplierReturnItem> resolvedItems = new ArrayList<>();

            for (SupplierReturnItemInput input : items) {
                ProductVariant variant = variantRepository.findById(conn, input.variantId())
                        .orElseThrow(() -> new EntityNotFoundException("ProductVariant id=" + input.variantId() + " not found"));

                Money lineTotal = input.calculateLineTotal();
                totalDebit = totalDebit.plus(lineTotal);

                resolvedItems.add(new SupplierReturnItem(
                        variant.id(),
                        variant.variantName(),
                        input.quantity(),
                        input.unitCostPrice(),
                        lineTotal,
                        input.reason()
                ));
            }

            FinancialYear fy = FinancialYear.ofDate(effectiveDate);

            // 4. Record supplier ledger entry (negative amount reduces what the shop owes the supplier)
            String noteText = notes != null && !notes.trim().isEmpty() ? notes.trim() : "Return to distributor: " + supplier.name();
            long entryId = supplierLedgerRepository.insertEntry(
                    conn,
                    supplierId,
                    "RETURN_OUT",
                    -totalDebit.getPaise(),
                    "RETURN_OUT",
                    0L,
                    effectiveDate.toString(),
                    noteText,
                    requestingUserId
            );

            // Generate debit note number formatted with entry ID and FY
            String debitNoteNumber = String.format("DN%s/%04d", fy.getBillPrefix().replace("FY", ""), entryId);

            // 5. Deduct stock movements for each returned variant
            for (SupplierReturnItem item : resolvedItems) {
                stockService.recordMovementInTransaction(
                        conn,
                        item.variantId(),
                        StockMovementType.RETURN_OUT,
                        item.quantity().negate(), // Negative quantity deducts stock
                        "RETURN_OUT",
                        entryId,
                        String.format("Distributor return: %s [%s]", debitNoteNumber, item.reason()),
                        requestingUserId
                );
            }

            // 6. Record audit log (FR-44)
            auditLogService.logEventInTransaction(
                    conn,
                    requestingUserId,
                    "SUPPLIER_RETURN",
                    "SUPPLIER",
                    String.valueOf(supplierId),
                    null,
                    debitNoteNumber + " (" + totalDebit.toRupeesString() + ")",
                    "Returned " + resolvedItems.size() + " items to " + supplier.name() + ": " + noteText
            );

            logger.info("Processed supplier return {} to supplierId={} totalDebit={} items={}",
                    debitNoteNumber, supplierId, totalDebit.toRupeesString(), resolvedItems.size());

            return new SupplierReturnResult(
                    debitNoteNumber,
                    supplierId,
                    supplier.name(),
                    effectiveDate,
                    totalDebit,
                    resolvedItems,
                    entryId,
                    notes
            );
        });
    }

    private void verifyOwner(Connection conn, long userId) throws SQLException {
        User user = userRepository.findById(conn, userId)
                .orElseThrow(() -> new EntityNotFoundException("User id=" + userId + " not found"));
        if (!user.isOwner()) {
            throw new UnauthorizedException("Only the shop owner is authorized to process supplier returns");
        }
    }
}
