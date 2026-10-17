package com.shopbilling.db;

import org.sqlite.SQLiteConfig;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Immutable configuration for SQLite database connections.
 * Enforces WAL mode, foreign keys, and safe busy timeout settings (PRD Section 10).
 */
public final class DatabaseConfig {

    private final String jdbcUrl;
    private final int busyTimeoutMs;
    private final boolean enforceForeignKeys;
    private final SQLiteConfig.JournalMode journalMode;
    private final SQLiteConfig.SynchronousMode synchronousMode;

    private DatabaseConfig(String jdbcUrl,
                           int busyTimeoutMs,
                           boolean enforceForeignKeys,
                           SQLiteConfig.JournalMode journalMode,
                           SQLiteConfig.SynchronousMode synchronousMode) {
        this.jdbcUrl = Objects.requireNonNull(jdbcUrl, "JDBC URL cannot be null");
        this.busyTimeoutMs = busyTimeoutMs;
        this.enforceForeignKeys = enforceForeignKeys;
        this.journalMode = Objects.requireNonNull(journalMode, "Journal mode cannot be null");
        this.synchronousMode = Objects.requireNonNull(synchronousMode, "Synchronous mode cannot be null");
    }

    /**
     * Standard production configuration pointing to a specific database file.
     */
    public static DatabaseConfig forFile(Path dbFile) {
        Objects.requireNonNull(dbFile, "Database file path cannot be null");
        String url = "jdbc:sqlite:" + dbFile.toAbsolutePath().normalize().toString();
        return new DatabaseConfig(
                url,
                5000,
                true,
                SQLiteConfig.JournalMode.WAL,
                SQLiteConfig.SynchronousMode.NORMAL
        );
    }

    /**
     * Configuration using a DataDirectoryHandler.
     */
    public static DatabaseConfig forDataDirectory(DataDirectoryHandler handler) {
        Objects.requireNonNull(handler, "DataDirectoryHandler cannot be null");
        handler.ensureDirectoriesExist();
        return forFile(handler.getDatabasePath());
    }

    /**
     * In-memory configuration for isolated unit tests.
     */
    public static DatabaseConfig forMemory(String dbName) {
        String url = "jdbc:sqlite:file:" + dbName + "?mode=memory&cache=shared";
        return new DatabaseConfig(
                url,
                5000,
                true,
                SQLiteConfig.JournalMode.MEMORY,
                SQLiteConfig.SynchronousMode.OFF
        );
    }

    public String getJdbcUrl() {
        return jdbcUrl;
    }

    public int getBusyTimeoutMs() {
        return busyTimeoutMs;
    }

    public boolean isEnforceForeignKeys() {
        return enforceForeignKeys;
    }

    public SQLiteConfig.JournalMode getJournalMode() {
        return journalMode;
    }

    public SQLiteConfig.SynchronousMode getSynchronousMode() {
        return synchronousMode;
    }

    /**
     * Builds SQLiteConfig object with the configured properties.
     */
    public SQLiteConfig toSQLiteConfig() {
        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(enforceForeignKeys);
        config.setJournalMode(journalMode);
        config.setBusyTimeout(busyTimeoutMs);
        config.setSynchronous(synchronousMode);
        return config;
    }
}
