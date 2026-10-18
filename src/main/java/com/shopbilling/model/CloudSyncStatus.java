package com.shopbilling.model;

/**
 * Status of an encrypted cloud backup upload task (PRD Section 7.11 FR-47).
 */
public enum CloudSyncStatus {
    PENDING,
    IN_PROGRESS,
    UPLOADED,
    FAILED
}
