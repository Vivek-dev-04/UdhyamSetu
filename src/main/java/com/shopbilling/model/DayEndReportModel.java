package com.shopbilling.model;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Detailed report model representing the complete day-end cash reconciliation audit sheet
 * (PRD Section 7.9, FR-38 - FR-40).
 */
public record DayEndReportModel(
        LocalDate date,
        boolean isClosed,
        Optional<DayEndClosingRecord> closingRecord,
        DayEndCashSummary cashSummary,
        List<ExpenseRecord> expenses
) {
    public DayEndReportModel {
        Objects.requireNonNull(date, "Date cannot be null");
        Objects.requireNonNull(closingRecord, "ClosingRecord optional cannot be null");
        Objects.requireNonNull(cashSummary, "CashSummary cannot be null");
        expenses = expenses == null ? List.of() : List.copyOf(expenses);
    }
}
