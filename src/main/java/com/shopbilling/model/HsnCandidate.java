package com.shopbilling.model;

import java.util.Objects;

/**
 * Immutable candidate classification proposed by the Intelligent HSN Classification Engine (Section 4).
 * Adheres strictly to Section 4 rules:
 * - Details why it matches (matchReason)
 * - Identifies supporting legal/trade source (supportingSource)
 * - Highlights missing technical product specifications (missingSpecifications)
 * - Explicitly tracks verification status (status)
 * - Displays matching priority rank (matchingPriority: 1 to 7)
 * - Never invents false confidence percentages
 */
public record HsnCandidate(
        String hsnCode,
        String officialDescription,
        int matchingPriority,
        String matchReason,
        String supportingSource,
        String missingSpecifications,
        ClassificationStatus status,
        Double suggestedGstRate,
        String technicalNotes
) {
    public HsnCandidate {
        Objects.requireNonNull(hsnCode, "hsnCode cannot be null");
        Objects.requireNonNull(officialDescription, "officialDescription cannot be null");
        Objects.requireNonNull(matchReason, "matchReason cannot be null");
        if (status == null) {
            status = ClassificationStatus.SUGGESTED;
        }
        if (suggestedGstRate == null) {
            suggestedGstRate = 18.0;
        }
    }

    public String getPriorityLabel() {
        return switch (matchingPriority) {
            case 1 -> "Priority 1: Previously Verified Exact Mapping";
            case 2 -> "Priority 2: Manufacturer Classification & Catalog";
            case 3 -> "Priority 3: Exact Trade Keyword & Alias Match";
            case 4 -> "Priority 4: Product Group / Category Default";
            case 5 -> "Priority 5: Official HSN Description Match";
            case 6 -> "Priority 6: Fuzzy Text / Specification Match";
            case 7 -> "Priority 7: AI-Assisted Classification Rule";
            default -> "Priority " + matchingPriority + ": General Match";
        };
    }
}
