package com.shopbilling.model;

import java.util.Objects;

/**
 * Breakdown of sales revenue, weighted average purchase cost (COGS), and gross margin
 * for an individual product variant (PRD Section 7.8, FR-37).
 */
public record ProductProfitRow(
        long variantId,
        String productName,
        String variantName,
        String brandName,
        Quantity quantitySold,
        Money revenue,
        Money weightedAvgUnitCost,
        Money cogs,
        Money grossMargin,
        double marginPercent
) {
    public ProductProfitRow {
        Objects.requireNonNull(productName, "Product name cannot be null");
        Objects.requireNonNull(quantitySold, "Quantity sold cannot be null");
        Objects.requireNonNull(revenue, "Revenue cannot be null");
        Objects.requireNonNull(weightedAvgUnitCost, "Weighted avg unit cost cannot be null");
        Objects.requireNonNull(cogs, "COGS cannot be null");
        Objects.requireNonNull(grossMargin, "Gross margin cannot be null");
    }
}
