package com.shopbilling.model;

import java.util.Objects;

/**
 * Immutable record representing a Product (PRD Section 7.2, FR-4 & FR-5).
 * Integrated with GST HSN code, GST tax rate, Product Group, and HSN Verification Status.
 */
public record Product(
        Long id,
        long brandId,
        long categoryId,
        String name,
        String unitCode,
        Long reorderLevelScaled,
        boolean isActive,
        String createdAt,
        String hsnCode,
        Double gstRate,
        Long groupId,
        ClassificationStatus hsnStatus
) {
    public Product {
        Objects.requireNonNull(name, "Product name cannot be null");
        Objects.requireNonNull(unitCode, "Unit code cannot be null");
        name = name.trim();
        unitCode = unitCode.trim().toUpperCase();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Product name cannot be blank");
        }
        if (hsnCode != null) {
            hsnCode = HsnItem.cleanCode(hsnCode);
            if (hsnCode.isEmpty()) {
                hsnCode = null;
            }
        }
        if (gstRate == null) {
            gstRate = 18.0;
        }
        if (hsnStatus == null) {
            hsnStatus = (hsnCode != null && !hsnCode.isBlank()) ? ClassificationStatus.VERIFIED : ClassificationStatus.PENDING;
        }
    }

    /**
     * Backward-compatible 10-parameter constructor (from V4 integration).
     */
    public Product(
            Long id,
            long brandId,
            long categoryId,
            String name,
            String unitCode,
            Long reorderLevelScaled,
            boolean isActive,
            String createdAt,
            String hsnCode,
            Double gstRate
    ) {
        this(id, brandId, categoryId, name, unitCode, reorderLevelScaled, isActive, createdAt, hsnCode, gstRate, null, null);
    }

    /**
     * Backward-compatible 8-parameter constructor for existing callers and tests.
     */
    public Product(
            Long id,
            long brandId,
            long categoryId,
            String name,
            String unitCode,
            Long reorderLevelScaled,
            boolean isActive,
            String createdAt
    ) {
        this(id, brandId, categoryId, name, unitCode, reorderLevelScaled, isActive, createdAt, null, 18.0, null, null);
    }
}
