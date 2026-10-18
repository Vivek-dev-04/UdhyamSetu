package com.shopbilling.model;

/**
 * Condition of goods returned by a customer (PRD Section 7.5, FR-22).
 * SELLABLE goods are added back to active inventory via RETURN_IN.
 * DAMAGED goods are tracked in the damage bucket and do not increase sellable inventory.
 */
public enum ReturnCondition {
    SELLABLE,
    DAMAGED;

    public static ReturnCondition fromString(String text) {
        if (text == null) {
            throw new IllegalArgumentException("ReturnCondition cannot be null");
        }
        return ReturnCondition.valueOf(text.trim().toUpperCase());
    }
}
