package com.shopbilling.model;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Domain model representing a completed sale (PRD Section 6, 7.3, 11).
 * Can be either an official bill (saleMode=BILL) with an assigned bill number,
 * or a registered counter sale (saleMode=REGISTERED) without a bill number.
 */
public record Sale(
        Long id,
        SaleMode saleMode,
        String financialYear,
        String billNumber,
        Integer billSequence,
        Long customerId,
        LocalDate saleDate,
        String createdAt,
        Money subtotal,
        Money discount,
        Money totalAmount,
        long createdByUserId,
        List<SaleItem> items,
        List<PaymentRecord> payments
) {
    public Sale {
        Objects.requireNonNull(saleMode, "Sale mode cannot be null");
        Objects.requireNonNull(financialYear, "Financial year cannot be null");
        Objects.requireNonNull(saleDate, "Sale date cannot be null");
        Objects.requireNonNull(subtotal, "Subtotal cannot be null");
        Objects.requireNonNull(discount, "Discount cannot be null");
        Objects.requireNonNull(totalAmount, "Total amount cannot be null");
        items = items == null ? List.of() : Collections.unmodifiableList(items);
        payments = payments == null ? List.of() : Collections.unmodifiableList(payments);
    }

    public boolean isBilled() {
        return saleMode == SaleMode.BILL && billNumber != null;
    }

    public boolean isRegistered() {
        return saleMode == SaleMode.REGISTERED;
    }
}
