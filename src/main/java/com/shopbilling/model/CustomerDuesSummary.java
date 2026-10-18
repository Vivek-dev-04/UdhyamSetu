package com.shopbilling.model;

import java.util.Objects;

/**
 * Summary DTO of a customer with outstanding dues for quick ledger overview.
 */
public record CustomerDuesSummary(
        long customerId,
        String customerName,
        String phone,
        CustomerType customerType,
        Money currentDues,
        Money creditLimit
) {
    public CustomerDuesSummary {
        Objects.requireNonNull(customerName, "Customer name cannot be null");
        Objects.requireNonNull(customerType, "Customer type cannot be null");
        Objects.requireNonNull(currentDues, "Current dues cannot be null");
    }
}
