package com.shopbilling.model;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Domain model representing an entry in a distributor/supplier ledger (PRD Section 7.6, FR-29, FR-30).
 * Append-only: ledger entries are never modified or deleted.
 */
public record SupplierLedgerEntry(
        Long id,
        long supplierId,
        String entryType,
        Money amount,
        String referenceType,
        long referenceId,
        LocalDate entryDate,
        String notes,
        String createdAt,
        long createdByUserId
) {
    public SupplierLedgerEntry {
        Objects.requireNonNull(entryType, "Entry type cannot be null");
        Objects.requireNonNull(amount, "Amount cannot be null");
        Objects.requireNonNull(referenceType, "Reference type cannot be null");
        Objects.requireNonNull(entryDate, "Entry date cannot be null");
    }

    public boolean isPurchaseCredit() {
        return "PURCHASE_CREDIT".equalsIgnoreCase(entryType);
    }

    public boolean isPaymentMade() {
        return "PAYMENT_MADE".equalsIgnoreCase(entryType);
    }
}
