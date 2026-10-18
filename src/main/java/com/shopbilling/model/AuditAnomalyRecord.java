package com.shopbilling.model;

import java.util.Objects;

/**
 * Single detected anomaly event flagged for owner review (PRD Section 7.10, Section 14).
 */
public record AuditAnomalyRecord(
        String id,
        AnomalyCategory category,
        AnomalySeverity severity,
        String title,
        String description,
        String entityRef,
        Long userId,
        String username,
        String occurredAt,
        Money monetaryAmount
) {
    public AuditAnomalyRecord {
        Objects.requireNonNull(id, "Anomaly ID cannot be null");
        Objects.requireNonNull(category, "Category cannot be null");
        Objects.requireNonNull(severity, "Severity cannot be null");
        Objects.requireNonNull(title, "Title cannot be null");
        Objects.requireNonNull(description, "Description cannot be null");
        Objects.requireNonNull(occurredAt, "OccurredAt cannot be null");
    }
}
