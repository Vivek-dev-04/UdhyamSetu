package com.shopbilling.model;

/**
 * Originating trigger type for database backups (PRD Section 7.11, FR-46, FR-49).
 */
public enum BackupType {
    SCHEDULED,
    DAY_END,
    PRE_MIGRATION,
    MANUAL
}
