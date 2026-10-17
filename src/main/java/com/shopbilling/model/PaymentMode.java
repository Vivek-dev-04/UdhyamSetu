package com.shopbilling.model;

/**
 * Supported payment modes for counter sales and returns (PRD Section 7.3, FR-11).
 */
public enum PaymentMode {
    CASH,
    UPI,
    CARD,
    CREDIT;

    public static PaymentMode fromString(String text) {
        if (text == null) {
            throw new IllegalArgumentException("PaymentMode cannot be null");
        }
        return PaymentMode.valueOf(text.trim().toUpperCase());
    }
}
