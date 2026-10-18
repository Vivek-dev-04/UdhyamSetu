package com.shopbilling.model;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Aggregated sales report result including itemized rows, counts, gross sales,
 * returns, net turnover, and payment breakdown (PRD Section 7.8, FR-33 - FR-36).
 */
public record SalesReportResult(
        SalesReportFilter filter,
        List<SalesSummaryRow> rows,
        int totalTransactions,
        int billedTransactions,
        int registeredTransactions,
        Money grossSales,
        Money totalReturns,
        Money netSales,
        Map<PaymentMode, Money> paymentModeTotals
) {
    public SalesReportResult {
        Objects.requireNonNull(grossSales, "Gross sales cannot be null");
        Objects.requireNonNull(totalReturns, "Total returns cannot be null");
        Objects.requireNonNull(netSales, "Net sales cannot be null");
        rows = rows == null ? List.of() : Collections.unmodifiableList(rows);
        paymentModeTotals = paymentModeTotals == null ? Map.of() : Collections.unmodifiableMap(paymentModeTotals);
    }
}
