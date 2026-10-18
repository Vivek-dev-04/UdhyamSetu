package com.shopbilling.model;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Domain model representing an official day-end closing record (PRD Section 7.9, FR-38 - FR-40).
 * Stores expected cash, owner-counted cash, and variance.
 */
public record DayEndClosingRecord(
        Long id,
        LocalDate closingDate,
        Money expectedCash,
        Money countedCash,
        Money difference,
        String notes,
        String closedAt,
        long closedByUserId
) {
    public DayEndClosingRecord {
        Objects.requireNonNull(closingDate, "Closing date cannot be null");
        Objects.requireNonNull(expectedCash, "Expected cash cannot be null");
        Objects.requireNonNull(countedCash, "Counted cash cannot be null");
        Objects.requireNonNull(difference, "Difference cannot be null");
    }
}
