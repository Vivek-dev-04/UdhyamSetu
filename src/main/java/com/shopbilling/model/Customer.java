package com.shopbilling.model;

import java.util.Objects;

/**
 * Immutable record representing a Customer (Retail or Technician)
 * (PRD Section 7.3, FR-10 & Section 7.6, FR-26).
 */
public record Customer(
        Long id,
        String name,
        String phone,
        CustomerType customerType,
        String address,
        Money creditLimit,
        String notes,
        String createdAt
) {
    public Customer {
        Objects.requireNonNull(name, "Customer name cannot be null");
        Objects.requireNonNull(customerType, "CustomerType cannot be null");
        name = name.trim();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Customer name cannot be blank");
        }
        if (phone != null) {
            phone = phone.trim();
            if (phone.isEmpty()) {
                phone = null;
            }
        }
        if (address != null) {
            address = address.trim();
            if (address.isEmpty()) {
                address = null;
            }
        }
        if (notes != null) {
            notes = notes.trim();
            if (notes.isEmpty()) {
                notes = null;
            }
        }
    }
}
