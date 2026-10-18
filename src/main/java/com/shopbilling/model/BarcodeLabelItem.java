package com.shopbilling.model;

import java.util.Objects;

/**
 * Immutable record representing item metadata for printable barcode labels (PRD Section 7.2 FR-5, Section 7.3 FR-8).
 */
public record BarcodeLabelItem(
        long variantId,
        String productName,
        String brandName,
        String variantName,
        String barcode,
        Money mrp,
        Money sellingPrice,
        int printCount
) {
    public BarcodeLabelItem {
        Objects.requireNonNull(productName, "Product name cannot be null");
        Objects.requireNonNull(barcode, "Barcode cannot be null");
        Objects.requireNonNull(mrp, "MRP cannot be null");
        Objects.requireNonNull(sellingPrice, "Selling price cannot be null");
        if (printCount <= 0) {
            throw new IllegalArgumentException("Print count must be strictly positive: " + printCount);
        }
    }

    public static BarcodeLabelItem of(long variantId, String productName, String brandName, String variantName,
                                      String barcode, Money mrp, Money sellingPrice) {
        return new BarcodeLabelItem(variantId, productName, brandName, variantName, barcode, mrp, sellingPrice, 1);
    }
}
