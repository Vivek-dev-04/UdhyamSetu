package com.shopbilling.model;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Immutable domain model representing a serial-tracked product warranty (PRD Section 11, Section 15.6).
 */
public record WarrantyRecord(
        Long id,
        long saleId,
        String billNumber,
        long variantId,
        String productName,
        String variantName,
        String serialNumber,
        int warrantyMonths,
        LocalDate warrantyStartDate,
        LocalDate warrantyEndDate,
        WarrantyStatus status,
        String customerName,
        String customerPhone,
        String claimNotes,
        String createdAt,
        long createdByUserId
) {
    public WarrantyRecord {
        Objects.requireNonNull(serialNumber, "Serial number cannot be null");
        Objects.requireNonNull(warrantyStartDate, "Warranty start date cannot be null");
        Objects.requireNonNull(warrantyEndDate, "Warranty end date cannot be null");
        Objects.requireNonNull(status, "Warranty status cannot be null");
        if (warrantyMonths <= 0) {
            throw new IllegalArgumentException("Warranty months must be strictly positive: " + warrantyMonths);
        }
    }

    public boolean isCurrentlyWithinWarranty(LocalDate currentDate) {
        if (status == WarrantyStatus.VOID || status == WarrantyStatus.CLAIMED) {
            return false;
        }
        return !currentDate.isAfter(warrantyEndDate);
    }
}
