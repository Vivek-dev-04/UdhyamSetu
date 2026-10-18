package com.shopbilling.model;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Aggregated Brand and Category contribution analysis result (PRD Section 2, Section 7.8, FR-37).
 */
public record CategoryBrandAnalyticsResult(
        FinancialYear financialYear,
        Quarter quarter,
        LocalDate fromDate,
        LocalDate toDate,
        List<BrandPerformanceRow> brandSummaries,
        List<CategoryPerformanceRow> categorySummaries,
        Money totalGrossSales,
        Money totalNetSales,
        Money totalGrossProfit
) {
    public CategoryBrandAnalyticsResult {
        Objects.requireNonNull(fromDate, "From date cannot be null");
        Objects.requireNonNull(toDate, "To date cannot be null");
        Objects.requireNonNull(totalGrossSales, "Total gross sales cannot be null");
        Objects.requireNonNull(totalNetSales, "Total net sales cannot be null");
        Objects.requireNonNull(totalGrossProfit, "Total gross profit cannot be null");
        brandSummaries = brandSummaries == null ? List.of() : Collections.unmodifiableList(brandSummaries);
        categorySummaries = categorySummaries == null ? List.of() : Collections.unmodifiableList(categorySummaries);
    }
}
