package com.shopbilling.model;

import java.util.Objects;

/**
 * Item input representing a counted quantity during physical inventory reconciliation (FR-17).
 */
public record PhysicalCountItemInput(
        long variantId,
        Quantity countedQuantity
) {
    public PhysicalCountItemInput {
        Objects.requireNonNull(countedQuantity, "Counted quantity cannot be null");
        if (countedQuantity.isNegative()) {
            throw new IllegalArgumentException("Physical counted quantity cannot be negative: " + countedQuantity);
        }
    }
}
