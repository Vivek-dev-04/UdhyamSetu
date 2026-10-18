package com.shopbilling.service;

import com.shopbilling.db.*;
import com.shopbilling.model.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ScheduledBackupService Tests (Automated Backups & Retention Rotation)")
class ScheduledBackupServiceTest {

    @TempDir
    Path tempDir;

    private ConnectionFactory connectionFactory;
    private TransactionHelper transactionHelper;
    private BackupRepository backupRepository;
    private SettingsRepository settingsRepository;
    private UserRepository userRepository;
    private AuditLogRepository auditLogRepository;
    private AuditLogService auditLogService;
    private BackupService backupService;
    private ScheduledBackupService scheduledBackupService;
    private IstClock clock;

    private Path backupDir;
    private Path dbFile;
    private long ownerUserId;

    @BeforeEach
    void setUp() throws IOException {
        dbFile = tempDir.resolve("scheduled_backup_test.db");
        backupDir = tempDir.resolve("backups");
        Files.createDirectories(backupDir);

        DatabaseConfig config = DatabaseConfig.forFile(dbFile);
        connectionFactory = new ConnectionFactory(config);
        new FlywayMigrator(connectionFactory).migrate();

        transactionHelper = new TransactionHelper(connectionFactory);
        backupRepository = new BackupRepository();
        settingsRepository = new SettingsRepository();
        userRepository = new UserRepository();
        auditLogRepository = new AuditLogRepository();
        auditLogService = new AuditLogService(transactionHelper, auditLogRepository, userRepository);
        clock = IstClock.system();

        ownerUserId = transactionHelper.execute(conn -> {
            User owner = userRepository.insert(conn, new User(
                    null, "owner", "hashed", UserRole.OWNER, "Shop Owner", true, null
            ));
            return owner.id();
        });

        backupService = new BackupService(
                connectionFactory,
                transactionHelper,
                backupRepository,
                settingsRepository,
                userRepository,
                auditLogService,
                backupDir,
                dbFile,
                clock
        );

        scheduledBackupService = new ScheduledBackupService(
                backupService,
                backupRepository,
                transactionHelper,
                settingsRepository,
                auditLogService,
                clock
        );
    }

    @AfterEach
    void tearDown() {
        if (scheduledBackupService != null) {
            scheduledBackupService.stop();
        }
    }

    @Test
    @DisplayName("triggerScheduledBackupNow creates verified scheduled backup and records success")
    void testTriggerScheduledBackup() {
        BackupRecord record = scheduledBackupService.triggerScheduledBackupNow(ownerUserId);

        assertNotNull(record);
        assertEquals(BackupType.SCHEDULED, record.backupType());
        assertEquals("SUCCESS", record.status());
        assertTrue(Files.exists(Path.of(record.backupFilePath())), "Backup file must exist on disk");
        assertTrue(record.fileSizeBytes() > 0, "Backup file size must be positive");

        assertNotNull(scheduledBackupService.getLastRunTime());
        assertTrue(scheduledBackupService.getLastStatus().startsWith("SUCCESS"));
    }

    @Test
    @DisplayName("Retention rotation automatically prunes excess backups from disk and database")
    void testRetentionPruning() {
        // Set retention count to 2
        scheduledBackupService.setRetentionCount(2, ownerUserId);
        assertEquals(2, scheduledBackupService.getRetentionCount());

        // Create 4 backups
        BackupRecord b1 = backupService.performBackup(BackupType.MANUAL, null);
        BackupRecord b2 = backupService.performBackup(BackupType.MANUAL, null);
        BackupRecord b3 = backupService.performBackup(BackupType.MANUAL, null);
        BackupRecord b4 = backupService.performBackup(BackupType.MANUAL, null);

        // Run pruning with retention=2
        int prunedCount = scheduledBackupService.pruneOldBackups(2, ownerUserId);
        assertEquals(2, prunedCount, "Should prune exactly 2 oldest backups");

        // Verify remaining backups in DB
        List<BackupRecord> remaining = transactionHelper.executeRead(conn -> backupRepository.getRecentBackups(conn, 10));
        assertEquals(2, remaining.size(), "Only 2 backups should remain in DB");

        // Oldest backups b1 and b2 should be deleted from disk
        assertFalse(Files.exists(Path.of(b1.backupFilePath())), "Oldest backup file should be deleted from disk");
        assertFalse(Files.exists(Path.of(b2.backupFilePath())), "Second oldest backup file should be deleted from disk");

        // Most recent backups b3 and b4 should still exist
        assertTrue(Files.exists(Path.of(b3.backupFilePath())), "Recent backup file b3 should still exist");
        assertTrue(Files.exists(Path.of(b4.backupFilePath())), "Recent backup file b4 should still exist");
    }

    @Test
    @DisplayName("Starting and stopping scheduled backup background executor")
    void testStartAndStopLifecycle() {
        assertFalse(scheduledBackupService.isRunning());

        scheduledBackupService.start(Duration.ofSeconds(10), Duration.ofMinutes(60));
        assertTrue(scheduledBackupService.isRunning());
        assertNotNull(scheduledBackupService.getNextRunTime());

        scheduledBackupService.stop();
        assertFalse(scheduledBackupService.isRunning());
        assertEquals("STOPPED", scheduledBackupService.getLastStatus());
    }

    @Test
    @DisplayName("Setting invalid retention count throws ValidationException")
    void testRetentionValidation() {
        assertThrows(ValidationException.class, () ->
                scheduledBackupService.setRetentionCount(0, ownerUserId)
        );
        assertThrows(ValidationException.class, () ->
                scheduledBackupService.setRetentionCount(-5, ownerUserId)
        );
    }
}
