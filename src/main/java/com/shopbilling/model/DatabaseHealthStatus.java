package com.shopbilling.model;

import java.util.Collections;
import java.util.List;

/**
 * Diagnostic metrics and integrity health status of the SQLite database (PRD Section 9, 10, FR-50).
 */
public record DatabaseHealthStatus(
        boolean isIntegrityOk,
        List<String> integrityErrors,
        boolean isForeignKeysOk,
        List<String> foreignKeyViolations,
        long dbFileSizeBytes,
        long walFileSizeBytes,
        long shmFileSizeBytes,
        long totalPageCount,
        long pageSizeBytes,
        long freePageCount,
        double fragmentationPercent,
        long diskFreeSpaceBytes,
        String sqliteVersion
) {
    public DatabaseHealthStatus {
        integrityErrors = integrityErrors == null ? List.of() : Collections.unmodifiableList(integrityErrors);
        foreignKeyViolations = foreignKeyViolations == null ? List.of() : Collections.unmodifiableList(foreignKeyViolations);
    }

    public boolean isHealthy() {
        return isIntegrityOk && isForeignKeysOk;
    }

    public long totalStorageBytes() {
        return dbFileSizeBytes + walFileSizeBytes + shmFileSizeBytes;
    }
}
