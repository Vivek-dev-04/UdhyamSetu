package com.shopbilling.model;

import java.util.Objects;

/**
 * Input DTO representing a line item return request.
 */
public record ReturnItemInput(
        long saleItemId,
        Quantity quantity,
        ReturnCondition condition,
        String reason
) {
    public ReturnItemInput {
        Objects.requireNonNull(quantity, "Quantity cannot be null");
        Objects.requireNonNull(condition, "Condition cannot be null");
        Objects.requireNonNull(reason, "Reason cannot be null");
        if (!quantity.isPositive()) {
            throw new IllegalArgumentException("Return quantity must be strictly positive: " + quantity);
        }
        if (reason.trim().isEmpty()) {
            throw new IllegalArgumentException("Reason cannot be blank");
        }
    }

    public static ReturnItemInput of(long saleItemId, Quantity quantity, ReturnCondition condition, String reason) {
        return new ReturnItemInput(saleItemId, quantity, condition, reason);
    }
}
