package com.shopbilling.model;

import java.util.Objects;

/**
 * Historical record of a database backup execution (PRD Section 7.11, FR-46, FR-49).
 */
public record BackupRecord(
        Long id,
        String backupFilePath,
        BackupType backupType,
        long fileSizeBytes,
        String status,
        String errorMessage,
        String createdAt
) {
    public BackupRecord {
        Objects.requireNonNull(backupFilePath, "Backup file path cannot be null");
        Objects.requireNonNull(backupType, "Backup type cannot be null");
        Objects.requireNonNull(status, "Status cannot be null");
    }

    public boolean isSuccess() {
        return "SUCCESS".equalsIgnoreCase(status);
    }
}
