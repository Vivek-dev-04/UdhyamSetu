package com.shopbilling.model;

import java.util.Objects;

/**
 * Immutable record representing a product Brand (e.g. Havells, Anchor, V-Guard)
 * (PRD Section 7.2, FR-4 & FR-7).
 */
public record Brand(Long id, String name, String createdAt) {

    public Brand {
        Objects.requireNonNull(name, "Brand name cannot be null");
        name = name.trim();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Brand name cannot be blank");
        }
    }
}
