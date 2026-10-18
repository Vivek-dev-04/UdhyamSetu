package com.shopbilling.model;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Summary row representation for a single sale transaction in the sales report grid.
 */
public record SalesSummaryRow(
        long saleId,
        SaleMode saleMode,
        String billNumber,
        LocalDate saleDate,
        Long customerId,
        String customerName,
        CustomerType customerType,
        Money subtotal,
        Money discount,
        Money totalAmount,
        String paymentModes
) {
    public SalesSummaryRow {
        Objects.requireNonNull(saleMode, "Sale mode cannot be null");
        Objects.requireNonNull(saleDate, "Sale date cannot be null");
        Objects.requireNonNull(subtotal, "Subtotal cannot be null");
        Objects.requireNonNull(discount, "Discount cannot be null");
        Objects.requireNonNull(totalAmount, "Total amount cannot be null");
    }
}
