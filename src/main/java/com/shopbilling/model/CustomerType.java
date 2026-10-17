package com.shopbilling.model;

/**
 * Customer types supported by the shop (PRD Section 7.3, FR-10).
 * Technicians may have different discount and pricing rules.
 */
public enum CustomerType {
    RETAIL,
    TECHNICIAN;

    public static CustomerType fromString(String text) {
        if (text == null || text.trim().isEmpty()) {
            return RETAIL;
        }
        return CustomerType.valueOf(text.trim().toUpperCase());
    }
}
