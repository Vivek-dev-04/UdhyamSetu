package com.shopbilling.model;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * Result record for a completed supplier/distributor stock return (FR-15, FR-29, FR-44).
 */
public record SupplierReturnResult(
        String debitNoteNumber,
        long supplierId,
        String supplierName,
        LocalDate returnDate,
        Money totalDebitAmount,
        List<SupplierReturnItem> items,
        long supplierLedgerEntryId,
        String notes
) {
    public SupplierReturnResult {
        Objects.requireNonNull(debitNoteNumber, "Debit note number cannot be null");
        Objects.requireNonNull(supplierName, "Supplier name cannot be null");
        Objects.requireNonNull(returnDate, "Return date cannot be null");
        Objects.requireNonNull(totalDebitAmount, "Total debit amount cannot be null");
        Objects.requireNonNull(items, "Items cannot be null");
    }
}
