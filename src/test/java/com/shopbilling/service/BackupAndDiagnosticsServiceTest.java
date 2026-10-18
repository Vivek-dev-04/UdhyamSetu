package com.shopbilling.service;

import com.shopbilling.db.AuditLogRepository;
import com.shopbilling.db.BackupRepository;
import com.shopbilling.db.ConnectionFactory;
import com.shopbilling.db.CustomerRepository;
import com.shopbilling.db.DatabaseConfig;
import com.shopbilling.db.FlywayMigrator;
import com.shopbilling.db.SettingsRepository;
import com.shopbilling.db.SupplierRepository;
import com.shopbilling.db.TransactionHelper;
import com.shopbilling.db.UserRepository;
import com.shopbilling.model.AuditLogRecord;
import com.shopbilling.model.BackupRecord;
import com.shopbilling.model.BackupType;
import com.shopbilling.model.Customer;
import com.shopbilling.model.CustomerType;
import com.shopbilling.model.User;
import com.shopbilling.model.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

class BackupAndDiagnosticsServiceTest {

    private ConnectionFactory connectionFactory;
    private TransactionHelper transactionHelper;
    private BackupRepository backupRepository;
    private SettingsRepository settingsRepository;
    private UserRepository userRepository;
    private AuditLogRepository auditLogRepository;
    private AuditLogService auditLogService;
    private BackupService backupService;
    private DiagnosticsService diagnosticsService;
    private CustomerSupplierService customerSupplierService;

    private Path liveDbPath;
    private Path backupDir;
    private Path logDir;

    private static final long OWNER_USER_ID = 1L;
    private static final long STAFF_USER_ID = 2L;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws Exception {
        liveDbPath = tempDir.resolve("shop_billing_live.db");
        backupDir = tempDir.resolve("backups");
        logDir = tempDir.resolve("logs");
        Files.createDirectories(backupDir);
        Files.createDirectories(logDir);

        // Create sample log file
        Files.writeString(logDir.resolve("shop-billing.log"), "2026-10-18 10:00:00 INFO Initializing app\n");

        DatabaseConfig config = DatabaseConfig.forFile(liveDbPath);
        connectionFactory = new ConnectionFactory(config);
        new FlywayMigrator(connectionFactory).migrate();

        transactionHelper = new TransactionHelper(connectionFactory);
        backupRepository = new BackupRepository();
        settingsRepository = new SettingsRepository();
        userRepository = new UserRepository();
        auditLogRepository = new AuditLogRepository();
        CustomerRepository customerRepository = new CustomerRepository();
        SupplierRepository supplierRepository = new SupplierRepository();

        auditLogService = new AuditLogService(transactionHelper, auditLogRepository, userRepository);
        customerSupplierService = new CustomerSupplierService(transactionHelper, customerRepository, supplierRepository);
        IstClock clock = IstClock.system();

        backupService = new BackupService(
                connectionFactory,
                transactionHelper,
                backupRepository,
                settingsRepository,
                userRepository,
                auditLogService,
                backupDir,
                liveDbPath,
                clock
        );

        diagnosticsService = new DiagnosticsService(
                backupService,
                liveDbPath,
                logDir,
                clock
        );

        // Seed users
        try (Connection conn = connectionFactory.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO users (id, username, password_hash, role, full_name) " +
                    "VALUES (1, 'owner', 'hash1', 'OWNER', 'Shop Owner');");
            stmt.execute("INSERT INTO users (id, username, password_hash, role, full_name) " +
                    "VALUES (2, 'staff', 'hash2', 'STAFF', 'Counter Staff');");
        }
    }

    @Test
    @DisplayName("Backup: execute online VACUUM INTO backup and verify file validity")
    void testPerformBackup() {
        assertTrue(backupService.isBackupOlderThan24Hours(), "No backups initially, should need backup");

        // Seed a customer before backup
        customerSupplierService.createCustomer("Ashok Electric", "9988776655", CustomerType.TECHNICIAN, null, null, null);

        // Perform manual backup
        BackupRecord record = backupService.performBackup(BackupType.MANUAL, null);
        assertNotNull(record.id());
        assertTrue(record.isSuccess());
        assertEquals(BackupType.MANUAL, record.backupType());
        assertTrue(record.fileSizeBytes() > 0);
        assertTrue(Files.exists(Paths.get(record.backupFilePath())));

        // 24h health check should now be false (healthy)
        assertFalse(backupService.isBackupOlderThan24Hours());

        Optional<BackupRecord> last = backupService.getLastSuccessfulBackup();
        assertTrue(last.isPresent());
        assertEquals(record.id(), last.get().id());
    }

    @Test
    @DisplayName("Restore: owner-only restore with data verification and safety backup")
    void testRestoreBackup() {
        // Step 1: Create initial customer and take backup
        Customer c1 = customerSupplierService.createCustomer("Original Customer", "9911001100", CustomerType.RETAIL, null, null, null);
        BackupRecord backupRecord = backupService.performBackup(BackupType.DAY_END, null);
        Path backupFile = Paths.get(backupRecord.backupFilePath());

        // Step 2: Modify live DB (add second customer)
        Customer c2 = customerSupplierService.createCustomer("New Customer After Backup", "9922002200", CustomerType.RETAIL, null, null, null);
        assertEquals(2, customerSupplierService.getAllCustomers().size());

        // Step 3: Staff cannot restore
        assertThrows(UnauthorizedException.class, () ->
                backupService.restoreBackup(STAFF_USER_ID, backupFile)
        );

        // Step 4: Owner restores from backup
        backupService.restoreBackup(OWNER_USER_ID, backupFile);

        // Step 5: Verify data rolled back to backup state (only 1 customer)
        List<Customer> restoredCustomers = customerSupplierService.getAllCustomers();
        assertEquals(1, restoredCustomers.size());
        assertEquals(c1.name(), restoredCustomers.get(0).name());

        // Step 6: Verify audit log recorded DATABASE_RESTORE
        List<AuditLogRecord> logs = auditLogService.getRecentLogs(OWNER_USER_ID, 10);
        assertTrue(logs.stream().anyMatch(l -> "DATABASE_RESTORE".equals(l.action())));
    }

    @Test
    @DisplayName("Diagnostics: generate summary and export diagnostic zip bundle")
    void testDiagnosticsExport(@TempDir Path exportDir) throws Exception {
        // Run a backup first so diagnostics has backup information
        backupService.performBackup(BackupType.SCHEDULED, null);

        // Build summary string
        String summary = diagnosticsService.buildDiagnosticsSummary();
        assertNotNull(summary);
        assertTrue(summary.contains("UdhyamSetu - Diagnostics and Support Report"));
        assertTrue(summary.contains("Application Version: 0.1.0-SNAPSHOT"));
        assertTrue(summary.contains("BACKUP STATUS"));

        // Export ZIP archive
        Path zipTarget = exportDir.resolve("udhyamsetu_diagnostics.zip");
        Path resultZip = diagnosticsService.exportDiagnostics(zipTarget);
        assertTrue(Files.exists(resultZip));
        assertTrue(Files.size(resultZip) > 0);

        // Verify ZIP contents
        try (ZipFile zipFile = new ZipFile(resultZip.toFile())) {
            ZipEntry summaryEntry = zipFile.getEntry("diagnostics_summary.txt");
            assertNotNull(summaryEntry, "Zip must contain diagnostics_summary.txt");

            ZipEntry logEntry = zipFile.getEntry("logs/shop-billing.log");
            assertNotNull(logEntry, "Zip must contain shop-billing.log from log directory");
        }
    }
}
