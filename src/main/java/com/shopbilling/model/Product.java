package com.shopbilling.model;

import java.util.Objects;

/**
 * Immutable record representing a Product (PRD Section 7.2, FR-4 & FR-5).
 */
public record Product(
        Long id,
        long brandId,
        long categoryId,
        String name,
        String unitCode,
        Long reorderLevelScaled,
        boolean isActive,
        String createdAt
) {
    public Product {
        Objects.requireNonNull(name, "Product name cannot be null");
        Objects.requireNonNull(unitCode, "Unit code cannot be null");
        name = name.trim();
        unitCode = unitCode.trim().toUpperCase();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Product name cannot be blank");
        }
    }
}
