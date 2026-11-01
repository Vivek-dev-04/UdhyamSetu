package com.shopbilling.model;

import java.util.Collections;
import java.util.List;

/**
 * Summary metrics of an Excel HSN import operation (Section 4).
 */
public record HsnImportResult(
        int totalRows,
        int importedCount,
        int updatedCount,
        int skippedCount,
        int failedCount,
        List<String> errors,
        long durationMs
) {
    public HsnImportResult {
        errors = errors == null ? List.of() : Collections.unmodifiableList(errors);
    }

    public boolean isSuccess() {
        return failedCount == 0 && (importedCount > 0 || updatedCount > 0 || skippedCount > 0);
    }
}
