package com.shopbilling.model;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Complete statement of account for a supplier including current payables, aging buckets,
 * and ledger transaction history.
 */
public record SupplierStatement(
        Supplier supplier,
        LocalDate asOfDate,
        Money currentPayable,
        AgingSummary aging,
        List<SupplierLedgerEntry> entries
) {
    public SupplierStatement {
        Objects.requireNonNull(supplier, "Supplier cannot be null");
        Objects.requireNonNull(asOfDate, "As-of date cannot be null");
        Objects.requireNonNull(currentPayable, "Current payable cannot be null");
        Objects.requireNonNull(aging, "Aging cannot be null");
        entries = entries == null ? List.of() : Collections.unmodifiableList(entries);
    }
}
