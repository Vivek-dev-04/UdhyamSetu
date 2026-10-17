package com.shopbilling.service;

import com.shopbilling.db.ProductVariantRepository;
import com.shopbilling.db.PurchaseRepository;
import com.shopbilling.db.SupplierLedgerRepository;
import com.shopbilling.db.SupplierRepository;
import com.shopbilling.db.TransactionHelper;
import com.shopbilling.model.Money;
import com.shopbilling.model.Purchase;
import com.shopbilling.model.PurchaseItem;
import com.shopbilling.model.PurchaseItemInput;
import com.shopbilling.model.StockMovementType;
import com.shopbilling.model.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Service managing inward stock from distributors/suppliers (PRD Section 7.4, FR-16).
 * Atomically writes purchase records, line items, stock ledger movements, and supplier ledger dues.
 * Strictly contains NO JavaFX imports.
 */
public class StockInService {

    private static final Logger logger = LoggerFactory.getLogger(StockInService.class);

    private final TransactionHelper transactionHelper;
    private final PurchaseRepository purchaseRepository;
    private final StockService stockService;
    private final SupplierRepository supplierRepository;
    private final SupplierLedgerRepository supplierLedgerRepository;
    private final ProductVariantRepository variantRepository;

    public StockInService(TransactionHelper transactionHelper,
                          PurchaseRepository purchaseRepository,
                          StockService stockService,
                          SupplierRepository supplierRepository,
                          SupplierLedgerRepository supplierLedgerRepository,
                          ProductVariantRepository variantRepository) {
        this.transactionHelper = Objects.requireNonNull(transactionHelper, "TransactionHelper cannot be null");
        this.purchaseRepository = Objects.requireNonNull(purchaseRepository, "PurchaseRepository cannot be null");
        this.stockService = Objects.requireNonNull(stockService, "StockService cannot be null");
        this.supplierRepository = Objects.requireNonNull(supplierRepository, "SupplierRepository cannot be null");
        this.supplierLedgerRepository = Objects.requireNonNull(supplierLedgerRepository, "SupplierLedgerRepository cannot be null");
        this.variantRepository = Objects.requireNonNull(variantRepository, "ProductVariantRepository cannot be null");
    }

    /**
     * Records inward stock from a distributor in ONE atomic database transaction (FR-16, Hard Rule 3).
     * 
     * @param supplierId The distributor supplier ID.
     * @param invoiceNumber Supplier's bill / invoice number.
     * @param invoiceDate Date of the distributor invoice.
     * @param items List of variant items, quantities, and cost prices.
     * @param paidAmount Amount paid immediately (cash, UPI, bank). If partial, remainder is on credit.
     * @param notes Optional notes.
     * @param userId The staff or owner recording the stock in.
     * @return The persisted Purchase with its assigned ID and items.
     */
    public Purchase recordStockIn(long supplierId,
                                  String invoiceNumber,
                                  LocalDate invoiceDate,
                                  List<PurchaseItemInput> items,
                                  Money paidAmount,
                                  String notes,
                                  long userId) {
        if (invoiceNumber == null || invoiceNumber.trim().isEmpty()) {
            throw new ValidationException("Supplier invoice number cannot be blank");
        }
        Objects.requireNonNull(invoiceDate, "Invoice date cannot be null");
        Objects.requireNonNull(paidAmount, "Paid amount cannot be null");

        if (items == null || items.isEmpty()) {
            throw new ValidationException("Cannot record stock-in with an empty items list");
        }
        if (paidAmount.isNegative()) {
            throw new ValidationException("Paid amount cannot be negative: " + paidAmount);
        }

        // Calculate total amount
        Money totalAmount = Money.ZERO;
        for (PurchaseItemInput item : items) {
            Money lineTotal = item.calculateLineTotal();
            totalAmount = totalAmount.plus(lineTotal);
        }

        if (paidAmount.isGreaterThan(totalAmount)) {
            throw new ValidationException(String.format(
                    "Paid amount (%s) cannot exceed total purchase invoice amount (%s)",
                    paidAmount, totalAmount
            ));
        }

        Money creditAmount = totalAmount.minus(paidAmount);
        String cleanInvoice = invoiceNumber.trim();

        Money finalTotalAmount = totalAmount;

        return transactionHelper.execute(conn -> {
            // 1. Verify supplier exists
            Supplier supplier = supplierRepository.findById(conn, supplierId)
                    .orElseThrow(() -> new EntityNotFoundException("Supplier id=" + supplierId + " not found"));

            // 2. Validate all variants exist before inserting
            for (PurchaseItemInput item : items) {
                variantRepository.findById(conn, item.variantId())
                        .orElseThrow(() -> new EntityNotFoundException("ProductVariant id=" + item.variantId() + " not found"));
            }

            // 3. Insert purchase record
            Purchase purchaseToCreate = new Purchase(
                    null,
                    supplierId,
                    cleanInvoice,
                    invoiceDate,
                    finalTotalAmount,
                    paidAmount,
                    creditAmount,
                    notes != null ? notes.trim() : null,
                    null,
                    userId,
                    List.of()
            );
            Purchase savedPurchase = purchaseRepository.insertPurchase(conn, purchaseToCreate);
            long purchaseId = savedPurchase.id();

            // 4. Insert line items and write stock movements
            List<PurchaseItem> savedItems = new ArrayList<>();
            for (PurchaseItemInput input : items) {
                Money lineTotal = input.calculateLineTotal();
                PurchaseItem itemEntity = new PurchaseItem(
                        null,
                        purchaseId,
                        input.variantId(),
                        input.quantity(),
                        input.costPrice(),
                        lineTotal
                );
                PurchaseItem savedItem = purchaseRepository.insertPurchaseItem(conn, itemEntity);
                savedItems.add(savedItem);

                // Write append-only stock movement
                stockService.recordMovementInTransaction(
                        conn,
                        input.variantId(),
                        StockMovementType.PURCHASE,
                        input.quantity(), // Positive quantity increases stock
                        "PURCHASE",
                        purchaseId,
                        "Invoice " + cleanInvoice + " from " + supplier.name(),
                        userId
                );
            }

            // 5. If bought on credit (or partial credit), record in supplier ledger
            if (creditAmount.isPositive()) {
                supplierLedgerRepository.insertEntry(
                        conn,
                        supplierId,
                        "PURCHASE_CREDIT",
                        creditAmount.getPaise(),
                        "PURCHASE",
                        purchaseId,
                        invoiceDate.toString(),
                        "Invoice " + cleanInvoice + " unpaid balance dues",
                        userId
                );
            }

            logger.info("Stock-in recorded successfully: purchaseId={} invoice='{}' supplier='{}' total={} paid={} credit={}",
                    purchaseId, cleanInvoice, supplier.name(), finalTotalAmount, paidAmount, creditAmount);

            return new Purchase(
                    purchaseId,
                    supplierId,
                    cleanInvoice,
                    invoiceDate,
                    finalTotalAmount,
                    paidAmount,
                    creditAmount,
                    notes != null ? notes.trim() : null,
                    savedPurchase.createdAt(),
                    userId,
                    savedItems
            );
        });
    }

    public Optional<Purchase> getPurchaseById(long id) {
        return transactionHelper.executeRead(conn -> purchaseRepository.findById(conn, id));
    }

    public List<Purchase> getPurchasesForSupplier(long supplierId) {
        return transactionHelper.executeRead(conn -> purchaseRepository.findBySupplierId(conn, supplierId));
    }

    public List<Purchase> getAllPurchases() {
        return transactionHelper.executeRead(purchaseRepository::findAll);
    }

    public Money getSupplierPayableBalance(long supplierId) {
        return transactionHelper.executeRead(conn ->
                Money.ofPaise(supplierLedgerRepository.getCurrentPayablePaise(conn, supplierId))
        );
    }
}
