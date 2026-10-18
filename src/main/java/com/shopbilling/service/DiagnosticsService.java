package com.shopbilling.service;

import com.shopbilling.model.BackupRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Service for collecting system diagnostic information and zipping rotating logs (PRD Section 7.11, FR-50).
 * Strictly contains NO JavaFX imports.
 */
public class DiagnosticsService {

    private static final Logger logger = LoggerFactory.getLogger(DiagnosticsService.class);
    public static final String APP_VERSION = "0.1.0-SNAPSHOT";

    private final BackupService backupService;
    private final Path liveDbPath;
    private final Path logDir;
    private final IstClock clock;

    public DiagnosticsService(BackupService backupService,
                              Path liveDbPath,
                              Path logDir,
                              IstClock clock) {
        this.backupService = Objects.requireNonNull(backupService, "BackupService cannot be null");
        this.liveDbPath = Objects.requireNonNull(liveDbPath, "Live DB path cannot be null");
        this.logDir = logDir != null ? logDir : resolveLogDirectory();
        this.clock = Objects.requireNonNull(clock, "IstClock cannot be null");
    }

    /**
     * Exports a complete diagnostics package into a zip file containing system info and logs (FR-50).
     *
     * @param outputZip Target zip file destination
     * @return Path to generated zip archive
     */
    public Path exportDiagnostics(Path outputZip) throws IOException {
        Objects.requireNonNull(outputZip, "Output zip path cannot be null");

        Path parent = outputZip.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(outputZip))) {
            // 1. Add diagnostics summary report
            String summaryContent = buildDiagnosticsSummary();
            ZipEntry summaryEntry = new ZipEntry("diagnostics_summary.txt");
            zos.putNextEntry(summaryEntry);
            zos.write(summaryContent.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            // 2. Add log files if log directory exists
            if (Files.exists(logDir) && Files.isDirectory(logDir)) {
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(logDir, "*.{log,gz,txt}")) {
                    for (Path logFile : stream) {
                        if (Files.isRegularFile(logFile)) {
                            ZipEntry logEntry = new ZipEntry("logs/" + logFile.getFileName().toString());
                            zos.putNextEntry(logEntry);
                            Files.copy(logFile, zos);
                            zos.closeEntry();
                        }
                    }
                }
            }
        }

        logger.info("Diagnostics export archive generated at: {}", outputZip);
        return outputZip;
    }

    /**
     * Builds human-readable system, runtime, and database diagnostics details.
     */
    public String buildDiagnosticsSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("=================================================================\n");
        sb.append(" UdhyamSetu - Diagnostics and Support Report (PRD FR-50)\n");
        sb.append("=================================================================\n\n");

        sb.append("Application Version: ").append(APP_VERSION).append("\n");
        sb.append("Export Timestamp:    ").append(clock.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)).append(" (IST)\n\n");

        sb.append("--- ENVIRONMENT & OPERATING SYSTEM ---\n");
        sb.append("OS Name:             ").append(System.getProperty("os.name")).append("\n");
        sb.append("OS Version:          ").append(System.getProperty("os.version")).append("\n");
        sb.append("OS Architecture:     ").append(System.getProperty("os.arch")).append("\n");
        sb.append("User Name:           ").append(System.getProperty("user.name")).append("\n");
        sb.append("User Home:           ").append(System.getProperty("user.home")).append("\n");
        sb.append("Working Directory:   ").append(System.getProperty("user.dir")).append("\n\n");

        sb.append("--- JAVA RUNTIME (JVM) ---\n");
        sb.append("Java Version:        ").append(System.getProperty("java.version")).append("\n");
        sb.append("Java Vendor:         ").append(System.getProperty("java.vendor")).append("\n");
        sb.append("Java Home:           ").append(System.getProperty("java.home")).append("\n");
        Runtime runtime = Runtime.getRuntime();
        long maxMb = runtime.maxMemory() / (1024 * 1024);
        long totalMb = runtime.totalMemory() / (1024 * 1024);
        long freeMb = runtime.freeMemory() / (1024 * 1024);
        sb.append(String.format("JVM Memory:          Max=%dMB, Total=%dMB, Free=%dMB, Used=%dMB\n\n",
                maxMb, totalMb, freeMb, totalMb - freeMb));

        sb.append("--- DATABASE HEALTH & STORAGE ---\n");
        sb.append("Database Path:       ").append(liveDbPath.toAbsolutePath()).append("\n");
        if (Files.exists(liveDbPath)) {
            try {
                long dbBytes = Files.size(liveDbPath);
                sb.append(String.format("Database Size:       %d bytes (%.2f MB)\n", dbBytes, dbBytes / (1024.0 * 1024.0)));
            } catch (IOException ignored) {}
        } else {
            sb.append("Database Status:     FILE NOT FOUND\n");
        }

        Path walPath = Paths.get(liveDbPath.toString() + "-wal");
        if (Files.exists(walPath)) {
            try {
                long walBytes = Files.size(walPath);
                sb.append(String.format("WAL File Size:       %d bytes (%.2f MB)\n", walBytes, walBytes / (1024.0 * 1024.0)));
            } catch (IOException ignored) {}
        }

        sb.append("\n--- BACKUP STATUS (FR-3, FR-46) ---\n");
        Optional<BackupRecord> lastBackup = backupService.getLastSuccessfulBackup();
        if (lastBackup.isPresent()) {
            BackupRecord b = lastBackup.get();
            sb.append("Last Backup Path:    ").append(b.backupFilePath()).append("\n");
            sb.append("Last Backup Type:    ").append(b.backupType()).append("\n");
            sb.append("Last Backup Date:    ").append(b.createdAt()).append("\n");
            sb.append("Last Backup Size:    ").append(b.fileSizeBytes()).append(" bytes\n");
            sb.append("Older than 24h:      ").append(backupService.isBackupOlderThan24Hours() ? "YES (ATTENTION NEEDED)" : "NO (HEALTHY)").append("\n");
        } else {
            sb.append("Last Backup:         NONE RECORDED (WARNING: NO BACKUP FOUND)\n");
        }

        return sb.toString();
    }

    private static Path resolveLogDirectory() {
        String env = System.getenv("SHOP_BILLING_LOG_DIR");
        if (env != null && !env.trim().isEmpty()) {
            return Paths.get(env.trim());
        }
        String prop = System.getProperty("SHOP_BILLING_LOG_DIR");
        if (prop != null && !prop.trim().isEmpty()) {
            return Paths.get(prop.trim());
        }
        return Paths.get("logs");
    }
}
