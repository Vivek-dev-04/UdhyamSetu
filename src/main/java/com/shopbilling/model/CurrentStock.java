package com.shopbilling.model;

import java.util.Objects;

/**
 * Derived stock representation calculated from the sum of stock ledger movements
 * (PRD Section 7.4, FR-15 & FR-18).
 */
public record CurrentStock(
        long variantId,
        String variantName,
        long productId,
        String productName,
        String brandName,
        String categoryName,
        String unitCode,
        Quantity currentStock,
        Quantity reorderLevel,
        boolean isLowStock
) {
    public CurrentStock {
        Objects.requireNonNull(variantName, "Variant name cannot be null");
        Objects.requireNonNull(productName, "Product name cannot be null");
        Objects.requireNonNull(unitCode, "Unit code cannot be null");
        Objects.requireNonNull(currentStock, "Current stock cannot be null");
    }
}
