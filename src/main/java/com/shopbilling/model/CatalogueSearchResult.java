package com.shopbilling.model;

import java.util.Objects;

/**
 * Result record from fast catalogue lookup and barcode scanning, enriched with live derived stock
 * (PRD Section 7.2, 7.3, FR-8, Section 13).
 */
public record CatalogueSearchResult(
        long variantId,
        long productId,
        String brandName,
        String categoryName,
        String productName,
        String variantName,
        String fullName,
        String unitCode,
        Money mrp,
        Money sellingPrice,
        String barcode,
        Quantity currentStock,
        boolean isLowStock,
        boolean isBarcodeMatch
) {
    public CatalogueSearchResult {
        Objects.requireNonNull(brandName, "Brand name cannot be null");
        Objects.requireNonNull(categoryName, "Category name cannot be null");
        Objects.requireNonNull(productName, "Product name cannot be null");
        Objects.requireNonNull(variantName, "Variant name cannot be null");
        Objects.requireNonNull(fullName, "Full name cannot be null");
        Objects.requireNonNull(unitCode, "Unit code cannot be null");
        Objects.requireNonNull(mrp, "MRP cannot be null");
        Objects.requireNonNull(sellingPrice, "Selling price cannot be null");
        Objects.requireNonNull(currentStock, "Current stock cannot be null");
    }
}
