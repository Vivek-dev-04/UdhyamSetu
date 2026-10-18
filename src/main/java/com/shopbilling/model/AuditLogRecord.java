package com.shopbilling.model;

import java.util.Objects;

/**
 * Append-only audit log entry (PRD Section 7.10, FR-44).
 * Tracks sensitive operations: discounts, price overrides, returns, stock adjustments,
 * logins, backups, and settings changes.
 */
public record AuditLogRecord(
        Long id,
        long userId,
        String username,
        String action,
        String entityName,
        String entityId,
        String oldValue,
        String newValue,
        String reason,
        String createdAt
) {
    public AuditLogRecord {
        Objects.requireNonNull(action, "Action cannot be null");
        Objects.requireNonNull(entityName, "Entity name cannot be null");
    }
}
