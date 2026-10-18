package com.shopbilling.model;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * Aggregated GST and CA compliance report result (PRD Section 6, 7.8, Section 15.1).
 */
public record GstReportResult(
        FinancialYear financialYear,
        Quarter quarter,
        LocalDate fromDate,
        LocalDate toDate,
        double gstRatePercent,
        List<GstInvoiceRow> invoices,
        List<GstSummaryRow> monthlySummaries,
        GstSummaryRow totalSummary
) {
    public GstReportResult {
        Objects.requireNonNull(fromDate, "From date cannot be null");
        Objects.requireNonNull(toDate, "To date cannot be null");
        Objects.requireNonNull(invoices, "Invoices list cannot be null");
        Objects.requireNonNull(monthlySummaries, "Monthly summaries cannot be null");
        Objects.requireNonNull(totalSummary, "Total summary cannot be null");
    }
}
