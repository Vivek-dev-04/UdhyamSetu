package com.shopbilling.model;

/**
 * Operational anomaly categories analyzed by the fraud watchdog (PRD Section 7.10, Section 14).
 */
public enum AnomalyCategory {
    EXCESSIVE_DISCOUNT,
    STOCK_SHRINKAGE,
    CASH_DRAWER_SHORTAGE,
    HIGH_VALUE_RETURN,
    AFTER_HOURS_ACTIVITY
}
