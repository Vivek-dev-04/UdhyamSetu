package com.shopbilling.model;

import java.util.List;
import java.util.Objects;

/**
 * Composite record representing a Product together with its Brand, Category, Unit,
 * and list of active Variants (PRD Section 7.2).
 */
public record ProductWithVariants(
        Product product,
        Brand brand,
        Category category,
        Unit unit,
        List<ProductVariant> variants
) {
    public ProductWithVariants {
        Objects.requireNonNull(product, "Product cannot be null");
        Objects.requireNonNull(brand, "Brand cannot be null");
        Objects.requireNonNull(category, "Category cannot be null");
        Objects.requireNonNull(unit, "Unit cannot be null");
        variants = variants != null ? List.copyOf(variants) : List.of();
    }
}
