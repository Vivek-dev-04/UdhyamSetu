package com.shopbilling.model;

import java.util.Objects;

/**
 * Value object representing receivables/payables aging broken down into standard buckets:
 * 0 to 30 days, 31 to 60 days, 61 to 90 days, and 90+ days (PRD Section 7.6, FR-28).
 */
public record AgingSummary(
        Money totalOutstanding,
        Money days0To30,
        Money days31To60,
        Money days61To90,
        Money days90Plus
) {
    public AgingSummary {
        Objects.requireNonNull(totalOutstanding, "Total outstanding cannot be null");
        Objects.requireNonNull(days0To30, "days0To30 cannot be null");
        Objects.requireNonNull(days31To60, "days31To60 cannot be null");
        Objects.requireNonNull(days61To90, "days61To90 cannot be null");
        Objects.requireNonNull(days90Plus, "days90Plus cannot be null");
    }

    public static AgingSummary zero() {
        return new AgingSummary(Money.ZERO, Money.ZERO, Money.ZERO, Money.ZERO, Money.ZERO);
    }
}
