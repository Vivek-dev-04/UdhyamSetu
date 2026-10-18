package com.shopbilling.model;

import java.util.Objects;

/**
 * Result of a manual stock adjustment or damage quarantine operation (PRD Section 7.4, FR-17, FR-22).
 */
public record StockAdjustmentResult(
        long variantId,
        String variantName,
        Quantity oldStock,
        Quantity newStock,
        Quantity delta,
        StockMovementType movementType,
        String reason,
        long movementId
) {
    public StockAdjustmentResult {
        Objects.requireNonNull(variantName, "Variant name cannot be null");
        Objects.requireNonNull(oldStock, "Old stock cannot be null");
        Objects.requireNonNull(newStock, "New stock cannot be null");
        Objects.requireNonNull(delta, "Delta cannot be null");
        Objects.requireNonNull(movementType, "Movement type cannot be null");
        Objects.requireNonNull(reason, "Reason cannot be null");
    }
}
