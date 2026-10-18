package com.shopbilling.model;

import java.util.Objects;

/**
 * Detailed quote for an item showing standard retail price, technician trade discount,
 * margin protection status, and effective unit price (PRD Section 5, Section 7.3 FR-10).
 */
public record TechnicianPriceQuote(
        long variantId,
        String variantName,
        Money mrp,
        Money retailSellingPrice,
        double discountPercent,
        Money unitPrice,
        Money savingsPerUnit,
        boolean marginProtected
) {
    public TechnicianPriceQuote {
        Objects.requireNonNull(variantName, "Variant name cannot be null");
        Objects.requireNonNull(mrp, "MRP cannot be null");
        Objects.requireNonNull(retailSellingPrice, "Retail selling price cannot be null");
        Objects.requireNonNull(unitPrice, "Effective unit price cannot be null");
        Objects.requireNonNull(savingsPerUnit, "Savings per unit cannot be null");
    }
}
