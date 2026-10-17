package com.shopbilling.model;

import java.util.Objects;

/**
 * Persisted line item of a sale (PRD Section 7.3, 11).
 */
public record SaleItem(
        Long id,
        long saleId,
        long variantId,
        Quantity quantity,
        Money unitPrice,
        Money lineDiscount,
        Money lineTotal
) {
    public SaleItem {
        Objects.requireNonNull(quantity, "Quantity cannot be null");
        Objects.requireNonNull(unitPrice, "Unit price cannot be null");
        Objects.requireNonNull(lineDiscount, "Line discount cannot be null");
        Objects.requireNonNull(lineTotal, "Line total cannot be null");
    }
}
