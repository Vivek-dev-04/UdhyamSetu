package com.shopbilling.model;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Detailed line record for B2B / B2C tax invoices included in GST report (PRD Section 6, 7.8).
 */
public record GstInvoiceRow(
        long saleId,
        String billNumber,
        LocalDate saleDate,
        String customerName,
        String customerGstin,
        CustomerType customerType,
        boolean isB2B,
        Money invoiceTotal,
        Money taxableValue,
        Money cgst,
        Money sgst,
        Money totalTax
) {
    public GstInvoiceRow {
        Objects.requireNonNull(billNumber, "Bill number cannot be null");
        Objects.requireNonNull(saleDate, "Sale date cannot be null");
        Objects.requireNonNull(customerName, "Customer name cannot be null");
        Objects.requireNonNull(invoiceTotal, "Invoice total cannot be null");
        Objects.requireNonNull(taxableValue, "Taxable value cannot be null");
        Objects.requireNonNull(cgst, "CGST cannot be null");
        Objects.requireNonNull(sgst, "SGST cannot be null");
        Objects.requireNonNull(totalTax, "Total tax cannot be null");
    }
}
