package com.shopbilling.model;

import java.util.Objects;

/**
 * Performance metrics aggregated for a specific Brand (PRD Section 2, Section 7.2, 7.8).
 */
public record BrandPerformanceRow(
        long brandId,
        String brandName,
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
    public BrandPerformanceRow {
        Objects.requireNonNull(brandName, "Brand name cannot be null");
        Objects.requireNonNull(grossSales, "Gross sales cannot be null");
        Objects.requireNonNull(returnsValue, "Returns value cannot be null");
        Objects.requireNonNull(netSales, "Net sales cannot be null");
        Objects.requireNonNull(cogs, "COGS cannot be null");
        Objects.requireNonNull(grossProfit, "Gross profit cannot be null");
    }
}
