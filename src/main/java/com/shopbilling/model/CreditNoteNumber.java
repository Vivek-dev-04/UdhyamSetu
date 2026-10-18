package com.shopbilling.model;

import java.util.Objects;

/**
 * Immutable value type representing a sequential, gap-free credit note number
 * assigned per financial year (PRD Section 7.5, FR-24).
 *
 * Example:
 * - FY: "2026-27"
 * - Prefix: "CN26-27"
 * - Sequence: 1 -> Formatted: "CN26-27/0001"
 */
public record CreditNoteNumber(
        String financialYear,
        String prefix,
        long sequenceNumber,
        String formattedNumber
) implements Comparable<CreditNoteNumber> {

    public CreditNoteNumber {
        Objects.requireNonNull(financialYear, "Financial year cannot be null");
        Objects.requireNonNull(prefix, "Prefix cannot be null");
        Objects.requireNonNull(formattedNumber, "Formatted number cannot be null");
        if (sequenceNumber <= 0) {
            throw new IllegalArgumentException("Sequence number must be positive: " + sequenceNumber);
        }
    }

    public static CreditNoteNumber of(String financialYear, String prefix, long sequenceNumber) {
        String formatted = String.format("%s/%04d", prefix.trim(), sequenceNumber);
        return new CreditNoteNumber(financialYear.trim(), prefix.trim(), sequenceNumber, formatted);
    }

    @Override
    public int compareTo(CreditNoteNumber o) {
        Objects.requireNonNull(o, "Cannot compare with null CreditNoteNumber");
        int fyComp = this.financialYear.compareTo(o.financialYear);
        if (fyComp != 0) {
            return fyComp;
        }
        return Long.compare(this.sequenceNumber, o.sequenceNumber);
    }

    @Override
    public String toString() {
        return formattedNumber;
    }
}
