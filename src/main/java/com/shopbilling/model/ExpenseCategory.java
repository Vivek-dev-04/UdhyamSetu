package com.shopbilling.model;

import java.util.Objects;

/**
 * Domain model representing an expense category (PRD Section 7.7, 11).
 */
public record ExpenseCategory(
        Long id,
        String name
) {
    public ExpenseCategory {
        Objects.requireNonNull(name, "Category name cannot be null");
        if (name.trim().isEmpty()) {
            throw new IllegalArgumentException("Category name cannot be blank");
        }
    }
}
