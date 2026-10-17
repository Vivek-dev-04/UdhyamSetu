package com.shopbilling.model;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * Immutable entity representing a purchase / stock in receipt from a distributor
 * (PRD Section 7.4, FR-16).
 */
public record Purchase(
        Long id,
        long supplierId,
        String invoiceNumber,
        LocalDate invoiceDate,
        Money totalAmount,
        Money paidAmount,
        Money creditAmount,
        String notes,
        String createdAt,
        long createdByUserId,
        List<PurchaseItem> items
) {
    public Purchase {
        Objects.requireNonNull(invoiceNumber, "Invoice number cannot be null");
        Objects.requireNonNull(invoiceDate, "Invoice date cannot be null");
        Objects.requireNonNull(totalAmount, "Total amount cannot be null");
        Objects.requireNonNull(paidAmount, "Paid amount cannot be null");
        Objects.requireNonNull(creditAmount, "Credit amount cannot be null");

        invoiceNumber = invoiceNumber.trim();
        if (invoiceNumber.isEmpty()) {
            throw new IllegalArgumentException("Invoice number cannot be blank");
        }

        items = items != null ? List.copyOf(items) : List.of();
    }
}
