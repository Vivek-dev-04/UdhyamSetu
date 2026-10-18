package com.shopbilling.model;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Complete statement of account for a customer including current dues, credit limit,
 * aging buckets, and full ledger transaction history.
 */
public record CustomerCreditStatement(
        Customer customer,
        LocalDate asOfDate,
        Money currentDues,
        Money creditLimit,
        Money availableCredit,
        AgingSummary aging,
        List<CustomerLedgerEntry> entries
) {
    public CustomerCreditStatement {
        Objects.requireNonNull(customer, "Customer cannot be null");
        Objects.requireNonNull(asOfDate, "As-of date cannot be null");
        Objects.requireNonNull(currentDues, "Current dues cannot be null");
        Objects.requireNonNull(aging, "Aging cannot be null");
        entries = entries == null ? List.of() : Collections.unmodifiableList(entries);
    }
}
