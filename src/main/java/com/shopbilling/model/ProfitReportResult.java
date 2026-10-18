package com.shopbilling.model;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Aggregated profit report result for owner view (PRD Section 7.8, FR-37).
 * Uses weighted average purchase cost for COGS and factors in all operating expenses.
 */
public record ProfitReportResult(
        LocalDate dateFrom,
        LocalDate dateTo,
        List<ProductProfitRow> productProfits,
        Money totalRevenue,
        Money totalCogs,
        Money grossProfit,
        Money totalExpenses,
        Money netProfit,
        double grossMarginPercent,
        double netMarginPercent
) {
    public ProfitReportResult {
        Objects.requireNonNull(totalRevenue, "Total revenue cannot be null");
        Objects.requireNonNull(totalCogs, "Total COGS cannot be null");
        Objects.requireNonNull(grossProfit, "Gross profit cannot be null");
        Objects.requireNonNull(totalExpenses, "Total expenses cannot be null");
        Objects.requireNonNull(netProfit, "Net profit cannot be null");
        productProfits = productProfits == null ? List.of() : Collections.unmodifiableList(productProfits);
    }
}
