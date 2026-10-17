package com.shopbilling.model;

import java.util.Objects;

/**
 * Immutable record representing a Product Variant (e.g. colour, rating, wattage, size)
 * with MRP, selling price, and optional barcode (PRD Section 7.2, FR-4 & FR-5).
 */
public record ProductVariant(
        Long id,
        long productId,
        String variantName,
        Money mrp,
        Money sellingPrice,
        String barcode,
        boolean isActive,
        String createdAt
) {
    public ProductVariant {
        Objects.requireNonNull(variantName, "Variant name cannot be null");
        Objects.requireNonNull(mrp, "MRP cannot be null");
        Objects.requireNonNull(sellingPrice, "Selling price cannot be null");

        variantName = variantName.trim();
        if (variantName.isEmpty()) {
            throw new IllegalArgumentException("Variant name cannot be blank");
        }

        if (barcode != null) {
            barcode = barcode.trim();
            if (barcode.isEmpty()) {
                barcode = null;
            }
        }
    }
}
