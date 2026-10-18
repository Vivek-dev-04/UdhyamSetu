package com.shopbilling.model;

import java.util.List;
import java.util.Objects;

/**
 * Summary of a multi-item price quote evaluated against customer trade tier (FR-10).
 */
public record TechnicianQuoteSummary(
        CustomerType customerType,
        List<TechnicianPriceQuote> itemQuotes,
        Money totalRetailSubtotal,
        Money totalEffectiveSubtotal,
        Money totalSavings
) {
    public TechnicianQuoteSummary {
        Objects.requireNonNull(customerType, "CustomerType cannot be null");
        Objects.requireNonNull(itemQuotes, "Item quotes cannot be null");
        Objects.requireNonNull(totalRetailSubtotal, "Total retail subtotal cannot be null");
        Objects.requireNonNull(totalEffectiveSubtotal, "Total effective subtotal cannot be null");
        Objects.requireNonNull(totalSavings, "Total savings cannot be null");
    }
}
