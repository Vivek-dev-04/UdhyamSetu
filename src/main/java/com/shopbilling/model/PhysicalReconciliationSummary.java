package com.shopbilling.model;

import java.util.List;
import java.util.Objects;

/**
 * Summary of a physical stock reconciliation audit run (PRD Section 7.4, Section 13).
 */
public record PhysicalReconciliationSummary(
        int totalAuditedItems,
        int adjustedCount,
        int matchedCount,
        List<StockAdjustmentResult> adjustments,
        String note
) {
    public PhysicalReconciliationSummary {
        Objects.requireNonNull(adjustments, "Adjustments list cannot be null");
    }
}
