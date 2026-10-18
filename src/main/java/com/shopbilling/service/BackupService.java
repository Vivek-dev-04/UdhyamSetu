package com.shopbilling.service;

import com.shopbilling.db.BackupRepository;
import com.shopbilling.db.ConnectionFactory;
import com.shopbilling.db.DatabaseConfig;
import com.shopbilling.db.FlywayMigrator;
import com.shopbilling.db.SettingsRepository;
import com.shopbilling.db.TransactionHelper;
import com.shopbilling.db.UserRepository;
import com.shopbilling.model.BackupRecord;
import com.shopbilling.model.BackupType;
import com.shopbilling.model.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Service managing SQLite online backups (VACUUM INTO), verification, restore, and 24h health checks
 * (PRD Section 7.1, 7.11, FR-3, FR-46, FR-48, FR-49).
 * Strictly contains NO JavaFX imports.
 */
public class BackupService {

    private static final Logger logger = LoggerFactory.getLogger(BackupService.class);
    private static final DateTimeFormatter FILE_TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final DateTimeFormatter DB_TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ConnectionFactory connectionFactory;
    private final TransactionHelper transactionHelper;
    private final BackupRepository backupRepository;
    private final SettingsRepository settingsRepository;
    private final UserRepository userRepository;
    private final AuditLogService auditLogService;
    private final Path defaultBackupDir;
    private final Path liveDbPath;
    private final IstClock clock;

    public BackupService(ConnectionFactory connectionFactory,
                         TransactionHelper transactionHelper,
                         BackupRepository backupRepository,
                         SettingsRepository settingsRepository,
                         UserRepository userRepository,
                         AuditLogService auditLogService,
                         Path defaultBackupDir,
                         Path liveDbPath,
                         IstClock clock) {
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "ConnectionFactory cannot be null");
        this.transactionHelper = Objects.requireNonNull(transactionHelper, "TransactionHelper cannot be null");
        this.backupRepository = Objects.requireNonNull(backupRepository, "BackupRepository cannot be null");
        this.settingsRepository = Objects.requireNonNull(settingsRepository, "SettingsRepository cannot be null");
        this.userRepository = Objects.requireNonNull(userRepository, "UserRepository cannot be null");
        this.auditLogService = Objects.requireNonNull(auditLogService, "AuditLogService cannot be null");
        this.defaultBackupDir = Objects.requireNonNull(defaultBackupDir, "Default backup directory cannot be null");
        this.liveDbPath = Objects.requireNonNull(liveDbPath, "Live DB path cannot be null");
        this.clock = Objects.requireNonNull(clock, "IstClock cannot be null");
    }

    /**
     * Executes a safe, online SQLite backup using VACUUM INTO (never raw copy of live file) (FR-46).
     *
     * @param backupType Trigger reason (DAY_END, SCHEDULED, PRE_MIGRATION, MANUAL)
     * @param customDir  Optional destination folder override
     * @return Persisted BackupRecord
     */
    public BackupRecord performBackup(BackupType backupType, Path customDir) {
        Objects.requireNonNull(backupType, "BackupType cannot be null");

        Path targetDir = resolveTargetDirectory(customDir);
        try {
            Files.createDirectories(targetDir);
        } catch (IOException e) {
            String errorMsg = "Failed to create backup target directory: " + targetDir;
            logger.error(errorMsg, e);
            recordFailedBackup("unknown", backupType, errorMsg);
            throw new RuntimeException(errorMsg, e);
        }

        String filename = String.format("udhyamsetu_backup_%s_%s.db",
                clock.now().format(FILE_TIMESTAMP_FORMAT),
                backupType.name().toLowerCase()
        );
        Path targetFile = targetDir.resolve(filename).toAbsolutePath().normalize();

        logger.info("Initiating SQLite online backup [type={}] -> {}", backupType, targetFile);

        try (Connection conn = connectionFactory.getConnection();
             Statement stmt = conn.createStatement()) {

            // VACUUM INTO creates a transactionally consistent copy of WAL-mode SQLite database
            String vacuumSql = "VACUUM INTO '" + targetFile.toString().replace("'", "''") + "';";
            stmt.execute(vacuumSql);

            // Verify integrity of the generated backup file (FR-48)
            verifyDatabaseIntegrity(targetFile);

            long fileSizeBytes = Files.size(targetFile);
            logger.info("Backup successfully created and verified: size={} bytes at {}", fileSizeBytes, targetFile);

            // Record successful backup log entry
            BackupRecord record = transactionHelper.execute(logConn ->
                    backupRepository.insert(logConn, targetFile.toString(), backupType, fileSizeBytes, "SUCCESS", null)
            );

            return record;

        } catch (Exception e) {
            String errorMsg = "Backup failed: " + e.getMessage();
            logger.error(errorMsg, e);
            recordFailedBackup(targetFile.toString(), backupType, errorMsg);
            throw new RuntimeException(errorMsg, e);
        }
    }

    /**
     * Owner-only database restore with pre-restore verification and safety backup (FR-48).
     *
     * @param requestingUserId User initiating restore (must be OWNER)
     * @param backupFilePath   Path to the verified backup file to restore
     */
    public void restoreBackup(long requestingUserId, Path backupFilePath) {
        Objects.requireNonNull(backupFilePath, "Backup file path cannot be null");

        if (!Files.exists(backupFilePath) || !Files.isRegularFile(backupFilePath)) {
            throw new ValidationException("Backup file not found at: " + backupFilePath);
        }

        // 1. Verify owner permission
        transactionHelper.executeRead(conn -> {
            User user = userRepository.findById(conn, requestingUserId)
                    .orElseThrow(() -> new EntityNotFoundException("User id=" + requestingUserId + " not found"));
            if (!user.isOwner()) {
                throw new UnauthorizedException("Only the shop owner is authorized to restore database backups");
            }
            return null;
        });

        // 2. Verify backup file integrity before touching live database
        logger.info("Verifying integrity of backup file before restore: {}", backupFilePath);
        verifyDatabaseIntegrity(backupFilePath);
        verifySchemaContents(backupFilePath);

        // 3. Take automatic safety backup of current database before restoring
        logger.info("Taking pre-restore safety backup of live database...");
        performBackup(BackupType.MANUAL, null);

        // 4. Perform the restore: flush WAL checkpoint and replace database file
        try (Connection conn = connectionFactory.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA wal_checkpoint(TRUNCATE);");
        } catch (SQLException e) {
            logger.warn("Could not truncate WAL before restore (proceeding with file replacement): {}", e.getMessage());
        }

        try {
            // Replace live DB file
            Files.copy(backupFilePath, liveDbPath, StandardCopyOption.REPLACE_EXISTING);

            // Delete stale WAL and SHM files if present
            Path walFile = Paths.get(liveDbPath.toString() + "-wal");
            Path shmFile = Paths.get(liveDbPath.toString() + "-shm");
            Files.deleteIfExists(walFile);
            Files.deleteIfExists(shmFile);

            logger.info("Restored database file replaced from: {}", backupFilePath);

            // Run Flyway migrations in case the restored DB was from an older version
            new FlywayMigrator(connectionFactory).migrate();

            // Verify integrity of newly restored live database
            verifyDatabaseIntegrity(liveDbPath);

            // Audit log the restoration
            auditLogService.logEvent(
                    requestingUserId,
                    "DATABASE_RESTORE",
                    "DATABASE",
                    liveDbPath.toString(),
                    null,
                    backupFilePath.toString(),
                    "Restored database from backup file"
            );

            logger.info("Database restore completed and verified successfully by owner id={}", requestingUserId);

        } catch (IOException e) {
            throw new RuntimeException("Failed to replace database file during restore: " + e.getMessage(), e);
        }
    }

    /**
     * Checks if the last successful backup is older than 24 hours (or if no backup exists) (FR-3).
     */
    public boolean isBackupOlderThan24Hours() {
        Optional<BackupRecord> last = getLastSuccessfulBackup();
        if (last.isEmpty()) {
            return true;
        }

        String createdAtStr = last.get().createdAt();
        if (createdAtStr == null || createdAtStr.trim().isEmpty()) {
            return true;
        }

        try {
            LocalDateTime backupTime = LocalDateTime.parse(createdAtStr.substring(0, 19), DB_TIMESTAMP_FORMAT);
            Duration age = Duration.between(backupTime, clock.now());
            return age.toHours() >= 24;
        } catch (Exception e) {
            logger.warn("Could not parse last backup timestamp '{}': {}", createdAtStr, e.getMessage());
            return true;
        }
    }

    public Optional<BackupRecord> getLastSuccessfulBackup() {
        return transactionHelper.executeRead(backupRepository::getLastSuccessfulBackup);
    }

    public List<BackupRecord> getRecentBackups(int limit) {
        return transactionHelper.executeRead(conn -> backupRepository.getRecentBackups(conn, limit));
    }

    private Path resolveTargetDirectory(Path customDir) {
        if (customDir != null) {
            return customDir.toAbsolutePath().normalize();
        }

        String configDir = transactionHelper.executeRead(conn ->
                settingsRepository.getSetting(conn, "BACKUP_EXTERNAL_DIRECTORY").orElse("")
        );

        if (configDir != null && !configDir.trim().isEmpty()) {
            return Paths.get(configDir.trim()).toAbsolutePath().normalize();
        }

        return defaultBackupDir.toAbsolutePath().normalize();
    }

    private void recordFailedBackup(String path, BackupType type, String error) {
        try {
            transactionHelper.execute(conn ->
                    backupRepository.insert(conn, path, type, 0L, "FAILED", error)
            );
        } catch (Exception ex) {
            logger.error("Failed to record failed backup log entry: {}", ex.getMessage());
        }
    }

    private void verifyDatabaseIntegrity(Path dbFile) {
        String url = "jdbc:sqlite:" + dbFile.toAbsolutePath().normalize().toString();
        try (Connection conn = DriverManager.getConnection(url);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA integrity_check;")) {

            if (rs.next()) {
                String result = rs.getString(1);
                if (!"ok".equalsIgnoreCase(result)) {
                    throw new IllegalStateException("Integrity check failed: " + result);
                }
            } else {
                throw new IllegalStateException("Integrity check returned no result");
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to verify database integrity for file: " + dbFile, e);
        }
    }

    private void verifySchemaContents(Path dbFile) {
        String url = "jdbc:sqlite:" + dbFile.toAbsolutePath().normalize().toString();
        try (Connection conn = DriverManager.getConnection(url);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name IN ('users', 'sales', 'products');"
             )) {

            if (rs.next() && rs.getInt(1) < 3) {
                throw new ValidationException("Backup file does not contain required tables (users, sales, products)");
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to verify schema in backup file: " + dbFile, e);
        }
    }
}
