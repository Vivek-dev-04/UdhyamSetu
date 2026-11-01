package com.shopbilling.model;

import java.util.Objects;

/**
 * Immutable audit record representing an imported HSN Excel/CSV batch (Section 3).
 * Stored in SQLite table hsn_import_history.
 */
public record HsnImportHistoryItem(
        Long id,
        String fileName,
        String fileHash,
        String sourceName,
        String sourceVersion,
        int totalRows,
        int importedCount,
        int updatedCount,
        int skippedCount,
        String status,
        Long importedByUserId,
        String importedAt
) {
    public HsnImportHistoryItem {
        Objects.requireNonNull(fileName, "fileName cannot be null");
        Objects.requireNonNull(sourceName, "sourceName cannot be null");
        Objects.requireNonNull(status, "status cannot be null");
    }
}
