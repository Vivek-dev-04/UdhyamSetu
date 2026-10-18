package com.shopbilling.db;

/**
 * Functional hook invoked immediately before executing pending database migrations
 * on an existing database (PRD Section 7.11, FR-49).
 */
@FunctionalInterface
public interface PreMigrationBackupHook {
    void performPreMigrationBackup() throws Exception;
}
