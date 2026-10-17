package com.shopbilling.model;

/**
 * Types of stock movements recorded in the append-only ledger (PRD Section 7.4, FR-15).
 */
public enum StockMovementType {
    PURCHASE,
    SALE,
    RETURN_IN,
    RETURN_OUT,
    DAMAGE,
    ADJUSTMENT;

    public static StockMovementType fromString(String text) {
        if (text == null) {
            throw new IllegalArgumentException("StockMovementType cannot be null");
        }
        return StockMovementType.valueOf(text.trim().toUpperCase());
    }
}
