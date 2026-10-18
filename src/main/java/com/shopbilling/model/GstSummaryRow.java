package com.shopbilling.model;

import java.util.Objects;

/**
 * Summary row for GST turnover and tax liability rollups (PRD Section 6, 7.8, Section 15.1).
 */
public record GstSummaryRow(
        String periodLabel,
        int billedInvoiceCount,
        Money grossBilledTurnover,
        Money creditNoteDeductions,
        Money netBilledTurnover,
        Money taxableTurnover,
        Money cgstAmount,
        Money sgstAmount,
        Money igstAmount,
        Money totalTax,
        Money registeredTurnover,
        Money totalTurnover
) {
    public GstSummaryRow {
        Objects.requireNonNull(periodLabel, "Period label cannot be null");
        Objects.requireNonNull(grossBilledTurnover, "Gross billed turnover cannot be null");
        Objects.requireNonNull(creditNoteDeductions, "Credit note deductions cannot be null");
        Objects.requireNonNull(netBilledTurnover, "Net billed turnover cannot be null");
        Objects.requireNonNull(taxableTurnover, "Taxable turnover cannot be null");
        Objects.requireNonNull(cgstAmount, "CGST amount cannot be null");
        Objects.requireNonNull(sgstAmount, "SGST amount cannot be null");
        Objects.requireNonNull(igstAmount, "IGST amount cannot be null");
        Objects.requireNonNull(totalTax, "Total tax cannot be null");
        Objects.requireNonNull(registeredTurnover, "Registered turnover cannot be null");
        Objects.requireNonNull(totalTurnover, "Total turnover cannot be null");
    }
}
