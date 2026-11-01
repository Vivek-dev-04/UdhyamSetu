package com.shopbilling.model;

import java.util.Objects;

/**
 * Immutable audit trail entry representing a classification modification, approval, or override (Section 10).
 * Stored in SQLite table hsn_verification_history.
 */
public record HsnVerificationAuditRecord(
        Long id,
        long productId,
        String action,
        String previousHsn,
        String newHsn,
        Double previousGstRate,
        Double newGstRate,
        Long userId,
        String reason,
        String verificationSource,
        String status,
        String createdAt
) {
    public HsnVerificationAuditRecord {
        Objects.requireNonNull(action, "action cannot be null");
        Objects.requireNonNull(status, "status cannot be null");
    }
}
