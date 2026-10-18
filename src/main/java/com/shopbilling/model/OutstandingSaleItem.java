package com.shopbilling.model;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Representation of an outstanding credit sale and its allocated payment status (PRD Section 7.6, FR-26, FR-27).
 */
public record OutstandingSaleItem(
        long saleId,
        String billNumber,
        LocalDate saleDate,
        Money totalAmount,
        Money creditAmount,
        Money allocatedAmount,
        Money outstandingBalance,
        boolean isFullyPaid
) {
    public OutstandingSaleItem {
        Objects.requireNonNull(saleDate, "Sale date cannot be null");
        Objects.requireNonNull(totalAmount, "Total amount cannot be null");
        Objects.requireNonNull(creditAmount, "Credit amount cannot be null");
        Objects.requireNonNull(allocatedAmount, "Allocated amount cannot be null");
        Objects.requireNonNull(outstandingBalance, "Outstanding balance cannot be null");
    }
}
