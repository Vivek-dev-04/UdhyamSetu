package com.shopbilling.model;

import java.util.Objects;

/**
 * Input DTO specifying an amount of a received payment allocated to a specific past credit sale.
 */
public record SaleAllocationInput(
        long saleId,
        Money amount
) {
    public SaleAllocationInput {
        Objects.requireNonNull(amount, "Amount cannot be null");
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("Allocation amount must be strictly positive: " + amount);
        }
    }

    public static SaleAllocationInput of(long saleId, Money amount) {
        return new SaleAllocationInput(saleId, amount);
    }
}
