package com.shopbilling.model;

import java.util.Objects;

/**
 * Immutable record representing a Supplier or distributor (PRD Section 7.4, FR-16 & Section 7.6, FR-29).
 */
public record Supplier(
        Long id,
        String name,
        String phone,
        String contactPerson,
        String address,
        String gstin,
        String notes,
        String createdAt
) {
    public Supplier {
        Objects.requireNonNull(name, "Supplier name cannot be null");
        name = name.trim();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Supplier name cannot be blank");
        }
        if (phone != null) {
            phone = phone.trim();
            if (phone.isEmpty()) {
                phone = null;
            }
        }
        if (contactPerson != null) {
            contactPerson = contactPerson.trim();
            if (contactPerson.isEmpty()) {
                contactPerson = null;
            }
        }
        if (address != null) {
            address = address.trim();
            if (address.isEmpty()) {
                address = null;
            }
        }
        if (gstin != null) {
            gstin = gstin.trim().toUpperCase();
            if (gstin.isEmpty()) {
                gstin = null;
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
