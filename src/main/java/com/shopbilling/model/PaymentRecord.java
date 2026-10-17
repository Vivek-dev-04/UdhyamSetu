package com.shopbilling.model;

import java.util.Objects;

/**
 * Immutable entity representing a persisted payment for a sale (PRD Section 7.3, FR-11).
 */
public record PaymentRecord(
        Long id,
        long saleId,
        PaymentMode mode,
        Money amount,
        String referenceNumber,
        String createdAt
) {
    public PaymentRecord {
        Objects.requireNonNull(mode, "PaymentMode cannot be null");
        Objects.requireNonNull(amount, "Payment amount cannot be null");
    }
}
