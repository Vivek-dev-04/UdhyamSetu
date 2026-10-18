package com.shopbilling.model;

import java.util.Objects;

/**
 * Domain model representing an allocation of a received customer payment to a specific sale (PRD Section 7.6, FR-27).
 */
public record PaymentAllocation(
        Long id,
        long paymentLedgerEntryId,
        long saleId,
        Money allocatedAmount,
        String createdAt
) {
    public PaymentAllocation {
        Objects.requireNonNull(allocatedAmount, "Allocated amount cannot be null");
        if (!allocatedAmount.isPositive()) {
            throw new IllegalArgumentException("Allocated amount must be strictly positive: " + allocatedAmount);
        }
    }
}
