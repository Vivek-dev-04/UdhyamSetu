package com.shopbilling.model;

import java.util.Objects;

/**
 * Immutable record representing a product Category or type (e.g. Switch, Socket, Light, Wire)
 * (PRD Section 7.2, FR-4 & FR-7).
 */
public record Category(Long id, String name, String createdAt) {

    public Category {
        Objects.requireNonNull(name, "Category name cannot be null");
        name = name.trim();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Category name cannot be blank");
        }
    }
}
