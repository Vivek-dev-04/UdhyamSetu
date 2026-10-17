package com.shopbilling.model;

import java.util.Objects;

/**
 * Immutable value type representing a sequential, gap-free bill number
 * assigned per financial year (PRD Section 6 & Hard Rule 6).
 * 
 * Example:
 * - FY: "2024-25"
 * - Prefix: "FY24-25"
 * - Sequence: 1 -> Formatted: "FY24-25/0001"
 */
public record BillNumber(
        String financialYear,
        String prefix,
        long sequenceNumber,
        String formattedNumber
) implements Comparable<BillNumber> {

    public BillNumber {
        Objects.requireNonNull(financialYear, "Financial year cannot be null");
        Objects.requireNonNull(prefix, "Prefix cannot be null");
        Objects.requireNonNull(formattedNumber, "Formatted number cannot be null");
        if (sequenceNumber <= 0) {
            throw new IllegalArgumentException("Sequence number must be positive: " + sequenceNumber);
        }
    }

    public static BillNumber of(String financialYear, String prefix, long sequenceNumber) {
        String formatted = String.format("%s/%04d", prefix.trim(), sequenceNumber);
        return new BillNumber(financialYear.trim(), prefix.trim(), sequenceNumber, formatted);
    }

    @Override
    public int compareTo(BillNumber o) {
        Objects.requireNonNull(o, "Cannot compare with null BillNumber");
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
