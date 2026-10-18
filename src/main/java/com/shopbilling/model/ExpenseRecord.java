package com.shopbilling.model;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Domain model representing an expense entry (PRD Section 7.7, FR-31, FR-32).
 * Expenses never affect stock or sales figures; they feed the profit view and day-end cash.
 */
public record ExpenseRecord(
        Long id,
        long categoryId,
        String categoryName,
        LocalDate expenseDate,
        Money amount,
        PaymentMode paymentMode,
        String note,
        String createdAt,
        long createdByUserId
) {
    public ExpenseRecord {
        Objects.requireNonNull(expenseDate, "Expense date cannot be null");
        Objects.requireNonNull(amount, "Amount cannot be null");
        Objects.requireNonNull(paymentMode, "Payment mode cannot be null");
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("Expense amount must be strictly positive: " + amount);
        }
    }
}
