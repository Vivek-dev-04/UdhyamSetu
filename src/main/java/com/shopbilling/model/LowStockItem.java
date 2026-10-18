package com.shopbilling.model;

import java.util.Objects;

/**
 * Detailed representation of an inventory item requiring replenishment (PRD Section 7.4, FR-18).
 */
public record LowStockItem(
        long variantId,
        String variantName,
        long productId,
        String productName,
        String brandName,
        String categoryName,
        String unitCode,
        Quantity currentStock,
        Quantity reorderLevel,
        Quantity shortage,
        Quantity suggestedOrderQuantity
) {
    public LowStockItem {
        Objects.requireNonNull(variantName, "Variant name cannot be null");
        Objects.requireNonNull(productName, "Product name cannot be null");
        Objects.requireNonNull(brandName, "Brand name cannot be null");
        Objects.requireNonNull(categoryName, "Category name cannot be null");
        Objects.requireNonNull(unitCode, "Unit code cannot be null");
        Objects.requireNonNull(currentStock, "Current stock cannot be null");
        Objects.requireNonNull(reorderLevel, "Reorder level cannot be null");
        Objects.requireNonNull(shortage, "Shortage cannot be null");
        Objects.requireNonNull(suggestedOrderQuantity, "Suggested order quantity cannot be null");
    }
}
