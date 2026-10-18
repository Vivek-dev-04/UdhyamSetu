package com.shopbilling.model;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Domain model representing an entry in a customer's credit ledger (PRD Section 7.6, FR-26, FR-30).
 * Append-only: ledger entries are never modified or deleted.
 */
public record CustomerLedgerEntry(
        Long id,
        long customerId,
        String entryType,
        Money amount,
        String referenceType,
        long referenceId,
        LocalDate entryDate,
        String notes,
        String createdAt,
        long createdByUserId
) {
    public CustomerLedgerEntry {
        Objects.requireNonNull(entryType, "Entry type cannot be null");
        Objects.requireNonNull(amount, "Amount cannot be null");
        Objects.requireNonNull(referenceType, "Reference type cannot be null");
        Objects.requireNonNull(entryDate, "Entry date cannot be null");
    }

    public boolean isCreditSale() {
        return "CREDIT_SALE".equalsIgnoreCase(entryType);
    }

    public boolean isPaymentReceived() {
        return "PAYMENT_RECEIVED".equalsIgnoreCase(entryType);
    }

    public boolean isReturnRefund() {
        return "RETURN_REFUND".equalsIgnoreCase(entryType);
    }
}
