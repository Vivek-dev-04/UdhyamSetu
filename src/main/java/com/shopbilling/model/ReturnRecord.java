package com.shopbilling.model;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Domain model representing a customer return event (PRD Section 7.5, 11).
 * Linked to the original sale and produces a credit note if the sale was billed.
 */
public record ReturnRecord(
        Long id,
        long saleId,
        String creditNoteNumber,
        String financialYear,
        LocalDate returnDate,
        PaymentMode refundMode,
        Money totalRefund,
        String notes,
        String createdAt,
        long createdByUserId,
        List<ReturnItemRecord> items
) {
    public ReturnRecord {
        Objects.requireNonNull(financialYear, "Financial year cannot be null");
        Objects.requireNonNull(returnDate, "Return date cannot be null");
        Objects.requireNonNull(refundMode, "Refund mode cannot be null");
        Objects.requireNonNull(totalRefund, "Total refund cannot be null");
        items = items == null ? List.of() : Collections.unmodifiableList(items);
    }
}
