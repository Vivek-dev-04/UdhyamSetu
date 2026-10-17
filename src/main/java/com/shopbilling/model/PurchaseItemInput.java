package com.shopbilling.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Input DTO representing a line item when recording stock in from a distributor (PRD Section 7.4, FR-16).
 */
public record PurchaseItemInput(
        long variantId,
        Quantity quantity,
        Money costPrice
) {
    public PurchaseItemInput {
        Objects.requireNonNull(quantity, "Quantity cannot be null");
        Objects.requireNonNull(costPrice, "Cost price cannot be null");
        if (quantity.isZero() || quantity.isNegative()) {
            throw new IllegalArgumentException("Quantity must be positive: " + quantity);
        }
        if (costPrice.isNegative()) {
            throw new IllegalArgumentException("Cost price cannot be negative: " + costPrice);
        }
    }

    /**
     * Calculates line total = quantity * costPrice rounded to nearest paise.
     */
    public Money calculateLineTotal() {
        BigDecimal qty = quantity.toBigDecimal();
        return costPrice.times(qty, RoundingMode.HALF_UP);
    }
}
