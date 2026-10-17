package com.shopbilling.model;

/**
 * Distinguishes between official billed sales and registered counter sales (PRD Section 6).
 */
public enum SaleMode {
    BILL,
    REGISTERED
}
