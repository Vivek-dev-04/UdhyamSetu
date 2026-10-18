package com.shopbilling.model;

import java.math.RoundingMode;
import java.util.Objects;

/**
 * Input record for returning an item to a distributor/supplier (PRD Section 7.4, 7.6, FR-15, FR-29).
 */
public record SupplierReturnItemInput(
        long variantId,
        Quantity quantity,
        Money unitCostPrice,
        String reason
) {
    public SupplierReturnItemInput {
        Objects.requireNonNull(quantity, "Return quantity cannot be null");
        Objects.requireNonNull(unitCostPrice, "Unit cost price cannot be null");
        Objects.requireNonNull(reason, "Return reason cannot be null");

        if (!quantity.isPositive()) {
            throw new IllegalArgumentException("Return quantity must be strictly positive: " + quantity);
        }
        if (unitCostPrice.isNegative()) {
            throw new IllegalArgumentException("Unit cost price cannot be negative: " + unitCostPrice);
        }
        if (reason.trim().isEmpty()) {
            throw new IllegalArgumentException("Return reason cannot be empty");
        }
    }

    public Money calculateLineTotal() {
        return unitCostPrice.times(quantity.toBigDecimal(), RoundingMode.HALF_UP);
    }
}
