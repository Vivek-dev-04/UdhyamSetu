package com.shopbilling.model;

import java.util.Objects;

/**
 * Immutable record representing an encrypted cloud backup queue entry (PRD Section 7.11 FR-47).
 */
public record CloudSyncRecord(
        Long id,
        String backupFileName,
        String encryptedFilePath,
        CloudSyncStatus status,
        int attempts,
        String lastError,
        String createdAt,
        String uploadedAt
) {
    public CloudSyncRecord {
        Objects.requireNonNull(backupFileName, "Backup file name cannot be null");
        Objects.requireNonNull(encryptedFilePath, "Encrypted file path cannot be null");
        Objects.requireNonNull(status, "Status cannot be null");
    }
}
