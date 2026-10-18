package com.shopbilling.model;

import java.util.Objects;

/**
 * Domain model representing a line item within a customer return (PRD Section 7.5, 11).
 */
public record ReturnItemRecord(
        Long id,
        long returnId,
        long saleItemId,
        long variantId,
        Quantity quantity,
        ReturnCondition condition,
        Money refundAmount,
        String reason
) {
    public ReturnItemRecord {
        Objects.requireNonNull(quantity, "Quantity cannot be null");
        Objects.requireNonNull(condition, "Condition cannot be null");
        Objects.requireNonNull(refundAmount, "Refund amount cannot be null");
        Objects.requireNonNull(reason, "Reason cannot be null");
    }
}
