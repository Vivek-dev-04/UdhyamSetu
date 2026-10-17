package com.shopbilling.model;

import java.util.Objects;

/**
 * Immutable record representing a Unit of Measurement (e.g. PIECE, METRE, COIL, BOX)
 * (PRD Section 7.2, FR-5 & FR-6).
 */
public record Unit(String code, String name, boolean allowsDecimal) {

    public Unit {
        Objects.requireNonNull(code, "Unit code cannot be null");
        Objects.requireNonNull(name, "Unit name cannot be null");
        code = code.trim().toUpperCase();
        name = name.trim();
        if (code.isEmpty() || name.isEmpty()) {
            throw new IllegalArgumentException("Unit code and name cannot be blank");
        }
    }
}
