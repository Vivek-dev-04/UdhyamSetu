package com.shopbilling.service;

import com.shopbilling.db.BackupRepository;
import com.shopbilling.db.SettingsRepository;
import com.shopbilling.db.TransactionHelper;
import com.shopbilling.model.BackupRecord;
import com.shopbilling.model.BackupType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Service managing automatic periodic database backups, verification, and retention rotation
 * (PRD Section 7.11, FR-46, FR-48).
 * Runs on a daemon thread so it never prevents application exit or blocks the UI.
 * Strictly contains NO JavaFX imports.
 */
public class ScheduledBackupService {

    private static final Logger logger = LoggerFactory.getLogger(ScheduledBackupService.class);
    public static final String SETTING_BACKUP_RETENTION_COUNT = "BACKUP_RETENTION_COUNT";
    public static final int DEFAULT_RETENTION_COUNT = 14;

    private final BackupService backupService;
    private final BackupRepository backupRepository;
    private final TransactionHelper transactionHelper;
    private final SettingsRepository settingsRepository;
    private final AuditLogService auditLogService;
    private final IstClock clock;

    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> scheduledTask;
    private Instant lastRunTime;
    private Instant nextRunTime;
    private String lastStatus = "IDLE";

    public ScheduledBackupService(BackupService backupService,
                                  BackupRepository backupRepository,
                                  TransactionHelper transactionHelper,
                                  SettingsRepository settingsRepository,
                                  AuditLogService auditLogService,
                                  IstClock clock) {
        this.backupService = Objects.requireNonNull(backupService, "BackupService cannot be null");
        this.backupRepository = Objects.requireNonNull(backupRepository, "BackupRepository cannot be null");
        this.transactionHelper = Objects.requireNonNull(transactionHelper, "TransactionHelper cannot be null");
        this.settingsRepository = Objects.requireNonNull(settingsRepository, "SettingsRepository cannot be null");
        this.auditLogService = Objects.requireNonNull(auditLogService, "AuditLogService cannot be null");
        this.clock = Objects.requireNonNull(clock, "IstClock cannot be null");
    }

    /**
     * Starts the background recurring scheduled backup runner.
     */
    public synchronized void start(Duration initialDelay, Duration interval) {
        Objects.requireNonNull(initialDelay, "initialDelay cannot be null");
        Objects.requireNonNull(interval, "interval cannot be null");

        if (scheduler != null && !scheduler.isShutdown()) {
            stop();
        }

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ScheduledBackupRunner");
            t.setDaemon(true);
            return t;
        });

        long initialSeconds = Math.max(0, initialDelay.toSeconds());
        long intervalSeconds = Math.max(60, interval.toSeconds());

        scheduledTask = scheduler.scheduleAtFixedRate(
                () -> {
                    try {
                        triggerScheduledBackupNow(1L);
                    } catch (Exception e) {
                        logger.error("Background scheduled backup failed: {}", e.getMessage(), e);
                    }
                },
                initialSeconds,
                intervalSeconds,
                TimeUnit.SECONDS
        );

        nextRunTime = clock.now().plus(initialDelay).toInstant();
        lastStatus = "SCHEDULED (interval: " + interval.toMinutes() + "m)";
        logger.info("ScheduledBackupService started: initialDelay={}s, interval={}s", initialSeconds, intervalSeconds);
    }

    /**
     * Stops the background scheduled backup runner.
     */
    public synchronized void stop() {
        if (scheduledTask != null) {
            scheduledTask.cancel(false);
            scheduledTask = null;
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        lastStatus = "STOPPED";
        logger.info("ScheduledBackupService stopped.");
    }

    public synchronized boolean isRunning() {
        return scheduler != null && !scheduler.isShutdown();
    }

    /**
     * Executes a scheduled backup cycle immediately:
     * 1. Creates hot backup via BackupService (VACUUM INTO).
     * 2. Verifies integrity of the newly created backup.
     * 3. Prunes backups that exceed the configured retention count.
     */
    public BackupRecord triggerScheduledBackupNow(long userId) {
        lastRunTime = clock.instant();
        lastStatus = "RUNNING";

        try {
            logger.info("Executing scheduled backup run by userId={}", userId);
            BackupRecord record = backupService.performBackup(BackupType.SCHEDULED, null);

            // Verify integrity
            Path backupFile = Paths.get(record.backupFilePath());
            boolean verified = backupService.verifyBackup(backupFile);
            if (!verified) {
                lastStatus = "VERIFICATION_FAILED";
                throw new RuntimeException("Integrity check failed for newly created backup: " + record.backupFilePath());
            }

            // Retention cleanup
            int retention = getRetentionCount();
            int prunedCount = pruneOldBackups(retention, userId);

            lastStatus = "SUCCESS" + (prunedCount > 0 ? " (pruned " + prunedCount + ")" : "");
            logger.info("Scheduled backup completed successfully: file={} size={} bytes, pruned={}",
                    record.backupFilePath(), record.fileSizeBytes(), prunedCount);

            return record;

        } catch (Exception e) {
            lastStatus = "FAILED: " + e.getMessage();
            logger.error("Scheduled backup execution error: {}", e.getMessage(), e);
            throw new RuntimeException("Scheduled backup failed: " + e.getMessage(), e);
        }
    }

    /**
     * Prunes excess backups older than retention limit from filesystem and database.
     */
    public int pruneOldBackups(int retentionCount, long userId) {
        if (retentionCount <= 0) {
            return 0;
        }

        return transactionHelper.execute(conn -> {
            List<BackupRecord> recent = backupRepository.getRecentBackups(conn, 500);
            if (recent.size() <= retentionCount) {
                return 0;
            }

            int pruned = 0;
            for (int i = retentionCount; i < recent.size(); i++) {
                BackupRecord toPrune = recent.get(i);
                try {
                    Path file = Paths.get(toPrune.backupFilePath());
                    Files.deleteIfExists(file);
                } catch (IOException e) {
                    logger.warn("Could not delete pruned backup file on disk: {}", toPrune.backupFilePath(), e);
                }

                backupRepository.deleteById(conn, toPrune.id());

                auditLogService.logEventInTransaction(
                        conn,
                        userId,
                        "BACKUP_PRUNED",
                        "BACKUP",
                        String.valueOf(toPrune.id()),
                        toPrune.backupFilePath(),
                        null,
                        "Pruned backup exceeding retention policy (" + retentionCount + " kept)"
                );
                pruned++;
            }
            logger.info("Pruned {} old backups exceeding retention limit of {}", pruned, retentionCount);
            return pruned;
        });
    }

    public int getRetentionCount() {
        return transactionHelper.executeRead(conn -> {
            String val = settingsRepository.getSettingOrDefault(conn, SETTING_BACKUP_RETENTION_COUNT, String.valueOf(DEFAULT_RETENTION_COUNT));
            try {
                return Math.max(1, Integer.parseInt(val.trim()));
            } catch (NumberFormatException e) {
                return DEFAULT_RETENTION_COUNT;
            }
        });
    }

    public void setRetentionCount(int count, long userId) {
        if (count < 1) {
            throw new ValidationException("Backup retention count must be at least 1");
        }
        transactionHelper.executeVoid(conn ->
                settingsRepository.setSetting(conn, SETTING_BACKUP_RETENTION_COUNT, String.valueOf(count), userId)
        );
    }

    public Instant getLastRunTime() {
        return lastRunTime;
    }

    public Instant getNextRunTime() {
        return nextRunTime;
    }

    public String getLastStatus() {
        return lastStatus;
    }
}
