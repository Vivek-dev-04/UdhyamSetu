package com.shopbilling.model;

import java.util.Objects;

/**
 * Immutable entity representing a line item in an inward stock purchase
 * (PRD Section 7.4, FR-16).
 */
public record PurchaseItem(
        Long id,
        long purchaseId,
        long variantId,
        Quantity quantity,
        Money costPrice,
        Money lineTotal
) {
    public PurchaseItem {
        Objects.requireNonNull(quantity, "Quantity cannot be null");
        Objects.requireNonNull(costPrice, "Cost price cannot be null");
        Objects.requireNonNull(lineTotal, "Line total cannot be null");
    }
}
