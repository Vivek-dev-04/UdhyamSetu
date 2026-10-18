package com.shopbilling.model;

import java.util.Objects;

/**
 * Performance metrics aggregated for a specific Product Category (PRD Section 2, Section 7.2, 7.8).
 */
public record CategoryPerformanceRow(
        long categoryId,
        String categoryName,
        int unitsSold,
        int unitsReturned,
        Money grossSales,
        Money returnsValue,
        Money netSales,
        Money cogs,
        Money grossProfit,
        double marginPercent,
        double returnRatePercent,
        double profitContributionPercent
) {
    public CategoryPerformanceRow {
        Objects.requireNonNull(categoryName, "Category name cannot be null");
        Objects.requireNonNull(grossSales, "Gross sales cannot be null");
        Objects.requireNonNull(returnsValue, "Returns value cannot be null");
        Objects.requireNonNull(netSales, "Net sales cannot be null");
        Objects.requireNonNull(cogs, "COGS cannot be null");
        Objects.requireNonNull(grossProfit, "Gross profit cannot be null");
    }
}
