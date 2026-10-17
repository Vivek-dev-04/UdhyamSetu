package com.shopbilling.model;

import java.math.RoundingMode;
import java.util.Objects;

/**
 * Line item input for registering a sale or generating a bill (PRD Section 7.3, FR-9).
 */
public record SaleItemInput(
        long variantId,
        Quantity quantity,
        Money unitPrice,
        Money lineDiscount
) {
    public SaleItemInput {
        Objects.requireNonNull(quantity, "Quantity cannot be null");
        Objects.requireNonNull(unitPrice, "Unit price cannot be null");
        if (lineDiscount == null) {
            lineDiscount = Money.ZERO;
        }
        if (quantity.isZero() || quantity.isNegative()) {
            throw new IllegalArgumentException("Quantity must be positive: " + quantity);
        }
        if (unitPrice.isNegative()) {
            throw new IllegalArgumentException("Unit price cannot be negative: " + unitPrice);
        }
        if (lineDiscount.isNegative()) {
            throw new IllegalArgumentException("Line discount cannot be negative: " + lineDiscount);
        }
        Money subtotal = unitPrice.times(quantity.toBigDecimal(), RoundingMode.HALF_UP);
        if (lineDiscount.isGreaterThan(subtotal)) {
            throw new IllegalArgumentException(String.format(
                    "Line discount (%s) cannot exceed line subtotal (%s)",
                    lineDiscount, subtotal
            ));
        }
    }

    public static SaleItemInput of(long variantId, Quantity quantity, Money unitPrice) {
        return new SaleItemInput(variantId, quantity, unitPrice, Money.ZERO);
    }

    public static SaleItemInput of(long variantId, Quantity quantity, Money unitPrice, Money lineDiscount) {
        return new SaleItemInput(variantId, quantity, unitPrice, lineDiscount);
    }

    /**
     * Calculates item subtotal = quantity * unitPrice (rounded half up to nearest paise).
     */
    public Money calculateSubtotal() {
        return unitPrice.times(quantity.toBigDecimal(), RoundingMode.HALF_UP);
    }

    /**
     * Calculates line total = subtotal - lineDiscount.
     */
    public Money calculateLineTotal() {
        return calculateSubtotal().minus(lineDiscount);
    }
}
