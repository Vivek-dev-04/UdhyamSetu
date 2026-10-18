package com.shopbilling.service;

import com.shopbilling.db.ConnectionFactory;
import com.shopbilling.model.DatabaseHealthStatus;
import com.shopbilling.model.WalCheckpointMode;
import com.shopbilling.model.WalCheckpointResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Service providing SQLite database integrity checks, WAL checkpointing, defragmentation,
 * and storage telemetry (PRD Section 9, 10, FR-50).
 * Pure service: strictly contains NO JavaFX imports.
 */
public class DatabaseMaintenanceService {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseMaintenanceService.class);

    private final ConnectionFactory connectionFactory;
    private final Path databaseFilePath;

    public DatabaseMaintenanceService(ConnectionFactory connectionFactory) {
        this(connectionFactory, extractPathFromConfig(connectionFactory));
    }

    public DatabaseMaintenanceService(ConnectionFactory connectionFactory, Path databaseFilePath) {
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "ConnectionFactory cannot be null");
        this.databaseFilePath = databaseFilePath;
    }

    private static Path extractPathFromConfig(ConnectionFactory factory) {
        String url = factory.getDatabaseConfig().getJdbcUrl();
        if (url != null && url.startsWith("jdbc:sqlite:") && !url.contains(":memory:")) {
            try {
                String sub = url.substring("jdbc:sqlite:".length());
                int queryIdx = sub.indexOf('?');
                if (queryIdx >= 0) {
                    sub = sub.substring(0, queryIdx);
                }
                return Path.of(sub);
            } catch (Exception ignored) {}
        }
        return null;
    }

    /**
     * Executes proactive integrity checks, foreign key checks, and storage metrics analysis.
     */
    public DatabaseHealthStatus checkDatabaseHealth() {
        try (Connection conn = connectionFactory.getConnection();
             Statement stmt = conn.createStatement()) {

            // 1. Quick Check / Integrity Check
            List<String> integrityErrors = new ArrayList<>();
            try (ResultSet rs = stmt.executeQuery("PRAGMA quick_check;")) {
                while (rs.next()) {
                    String res = rs.getString(1);
                    if (!"ok".equalsIgnoreCase(res)) {
                        integrityErrors.add(res);
                    }
                }
            }
            if (!integrityErrors.isEmpty()) {
                // If quick_check flagged issues, run deep integrity_check
                try (ResultSet rs = stmt.executeQuery("PRAGMA integrity_check(50);")) {
                    integrityErrors.clear();
                    while (rs.next()) {
                        String res = rs.getString(1);
                        if (!"ok".equalsIgnoreCase(res)) {
                            integrityErrors.add(res);
                        }
                    }
                }
            }

            // 2. Foreign Key Check
            List<String> fkErrors = new ArrayList<>();
            try (ResultSet rs = stmt.executeQuery("PRAGMA foreign_key_check;")) {
                while (rs.next()) {
                    String table = rs.getString(1);
                    long rowid = rs.getLong(2);
                    String parent = rs.getString(3);
                    int fkid = rs.getInt(4);
                    fkErrors.add(String.format("FK violation in table '%s' rowid %d -> parent '%s' (fkid=%d)",
                            table, rowid, parent, fkid));
                }
            }

            // 3. Storage Telemetry Pragmas
            long pageCount = 0L;
            try (ResultSet rs = stmt.executeQuery("PRAGMA page_count;")) {
                if (rs.next()) pageCount = rs.getLong(1);
            }

            long pageSize = 4096L;
            try (ResultSet rs = stmt.executeQuery("PRAGMA page_size;")) {
                if (rs.next()) pageSize = rs.getLong(1);
            }

            long freePages = 0L;
            try (ResultSet rs = stmt.executeQuery("PRAGMA freelist_count;")) {
                if (rs.next()) freePages = rs.getLong(1);
            }

            String version = "unknown";
            try (ResultSet rs = stmt.executeQuery("SELECT sqlite_version();")) {
                if (rs.next()) version = rs.getString(1);
            }

            double fragPercent = pageCount > 0 ? ((double) freePages * 100.0 / (double) pageCount) : 0.0;

            // 4. File-system metrics
            long dbSize = 0L;
            long walSize = 0L;
            long shmSize = 0L;
            long diskFree = 0L;

            if (databaseFilePath != null) {
                if (Files.exists(databaseFilePath)) {
                    dbSize = Files.size(databaseFilePath);
                    diskFree = databaseFilePath.toFile().getUsableSpace();
                }

                Path walPath = Path.of(databaseFilePath.toString() + "-wal");
                if (Files.exists(walPath)) {
                    walSize = Files.size(walPath);
                }

                Path shmPath = Path.of(databaseFilePath.toString() + "-shm");
                if (Files.exists(shmPath)) {
                    shmSize = Files.size(shmPath);
                }
            }

            DatabaseHealthStatus status = new DatabaseHealthStatus(
                    integrityErrors.isEmpty(),
                    integrityErrors,
                    fkErrors.isEmpty(),
                    fkErrors,
                    dbSize,
                    walSize,
                    shmSize,
                    pageCount,
                    pageSize,
                    freePages,
                    fragPercent,
                    diskFree,
                    version
            );

            logger.info("Database health check: healthy={} integrityOk={} fkOk={} dbSize={} walSize={} pages={}",
                    status.isHealthy(), status.isIntegrityOk(), status.isForeignKeysOk(),
                    dbSize, walSize, pageCount);

            return status;
        } catch (Exception e) {
            logger.error("Failed to execute database health check: {}", e.getMessage(), e);
            throw new RuntimeException("Database health check failed: " + e.getMessage(), e);
        }
    }

    /**
     * Checkpoints the Write-Ahead Log (WAL) to prevent log file bloat and sync dirty pages into the main DB.
     */
    public WalCheckpointResult checkpointWal(WalCheckpointMode mode) {
        final WalCheckpointMode effectiveMode = mode != null ? mode : WalCheckpointMode.TRUNCATE;
        String sql = "PRAGMA wal_checkpoint(" + effectiveMode.name() + ");";

        try (Connection conn = connectionFactory.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            int busy = 0;
            int log = 0;
            int checkpointed = 0;

            if (rs.next()) {
                busy = rs.getInt(1);
                log = rs.getInt(2);
                checkpointed = rs.getInt(3);
            }

            logger.info("WAL checkpoint ({}): busy={} logPages={} checkpointedPages={}",
                    effectiveMode, busy, log, checkpointed);

            return new WalCheckpointResult(effectiveMode, busy, log, checkpointed);
        } catch (SQLException e) {
            logger.error("Failed to execute WAL checkpoint: {}", e.getMessage(), e);
            throw new RuntimeException("WAL checkpoint failed: " + e.getMessage(), e);
        }
    }

    /**
     * Updates SQLite internal query planner statistics to ensure fast index scans for billing & reports.
     */
    public void optimizeQueryPlanner() {
        try (Connection conn = connectionFactory.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA optimize;");
            logger.info("Executed PRAGMA optimize for SQLite query planner");
        } catch (SQLException e) {
            logger.warn("PRAGMA optimize warning: {}", e.getMessage());
        }
    }

    /**
     * Defragments the SQLite database and shrinks database file size on disk.
     */
    public void vacuumDatabase() {
        try (Connection conn = connectionFactory.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("VACUUM;");
            logger.info("Executed SQLite VACUUM successfully");
        } catch (SQLException e) {
            logger.error("Failed to VACUUM database: {}", e.getMessage(), e);
            throw new RuntimeException("Database VACUUM failed: " + e.getMessage(), e);
        }
    }
}
