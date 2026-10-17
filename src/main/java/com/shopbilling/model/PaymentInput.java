package com.shopbilling.model;

import java.util.Objects;

/**
 * Input DTO representing a payment component within a split payment transaction
 * (PRD Section 7.3, FR-11).
 */
public record PaymentInput(
        PaymentMode mode,
        Money amount,
        String referenceNumber
) {
    public PaymentInput {
        Objects.requireNonNull(mode, "PaymentMode cannot be null");
        Objects.requireNonNull(amount, "Payment amount cannot be null");
        if (amount.isNegative() || amount.isZero()) {
            throw new IllegalArgumentException("Payment amount must be strictly positive: " + amount);
        }
        if (referenceNumber != null) {
            referenceNumber = referenceNumber.trim();
            if (referenceNumber.isEmpty()) {
                referenceNumber = null;
            }
        }
    }

    public static PaymentInput of(PaymentMode mode, Money amount) {
        return new PaymentInput(mode, amount, null);
    }

    public static PaymentInput of(PaymentMode mode, Money amount, String referenceNumber) {
        return new PaymentInput(mode, amount, referenceNumber);
    }
}
