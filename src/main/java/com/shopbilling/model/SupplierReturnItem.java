package com.shopbilling.model;

import java.util.Objects;

/**
 * Itemized line entry representing stock returned to a distributor/supplier (FR-15, FR-29).
 */
public record SupplierReturnItem(
        long variantId,
        String variantName,
        Quantity quantity,
        Money unitCostPrice,
        Money lineTotal,
        String reason
) {
    public SupplierReturnItem {
        Objects.requireNonNull(variantName, "Variant name cannot be null");
        Objects.requireNonNull(quantity, "Quantity cannot be null");
        Objects.requireNonNull(unitCostPrice, "Unit cost price cannot be null");
        Objects.requireNonNull(lineTotal, "Line total cannot be null");
        Objects.requireNonNull(reason, "Reason cannot be null");
    }
}
