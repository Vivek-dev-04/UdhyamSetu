package com.shopbilling.service;

import com.shopbilling.db.CloudSyncRepository;
import com.shopbilling.db.ConnectionFactory;
import com.shopbilling.db.DatabaseConfig;
import com.shopbilling.db.FlywayMigrator;
import com.shopbilling.db.TransactionHelper;
import com.shopbilling.model.CloudSyncRecord;
import com.shopbilling.model.CloudSyncStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CloudBackupSyncService Tests (AES-256-GCM Encrypted Cloud Disaster Recovery)")
class CloudBackupSyncServiceTest {

    @TempDir
    Path tempDir;

    private ConnectionFactory connectionFactory;
    private TransactionHelper transactionHelper;
    private CloudSyncRepository repository;
    private Path spoolDir;
    private IstClock clock;

    private CloudBackupSyncService syncService;

    @BeforeEach
    void setUp() throws IOException {
        Path dbPath = tempDir.resolve("test_cloud_sync.db");
        DatabaseConfig config = DatabaseConfig.forFile(dbPath);
        connectionFactory = new ConnectionFactory(config);
        new FlywayMigrator(connectionFactory).migrate();

        transactionHelper = new TransactionHelper(connectionFactory);
        repository = new CloudSyncRepository();
        spoolDir = tempDir.resolve("cloud_spool");
        clock = IstClock.fixedAtStartOfDay(LocalDate.of(2026, 6, 15));

        syncService = new CloudBackupSyncService(transactionHelper, repository, spoolDir, clock);
    }

    @Test
    @DisplayName("Should reject invalid passphrase or missing backup file")
    void testValidationFailures() throws IOException {
        Path dummyFile = tempDir.resolve("dummy.db");
        Files.writeString(dummyFile, "dummy content");

        // Short passphrase
        assertThrows(ValidationException.class, () ->
                syncService.enqueueBackupForUpload(dummyFile, "short")
        );

        // Missing file
        Path missing = tempDir.resolve("non_existent.db");
        assertThrows(ValidationException.class, () ->
                syncService.enqueueBackupForUpload(missing, "SecurePassphrase123!")
        );
    }

    @Test
    @DisplayName("Should encrypt file with AES-256-GCM and verify roundtrip decryption")
    void testEncryptionAndDecryptionRoundtrip() throws Exception {
        Path originalBackup = tempDir.resolve("backup_2026_06_15.db");
        String originalContent = "SQLite format 3\0 Mock database header with sensitive shop sales and ledgers";
        Files.writeString(originalBackup, originalContent, StandardCharsets.UTF_8);

        String passphrase = "OwnerMasterPassword999#";
        CloudSyncRecord record = syncService.enqueueBackupForUpload(originalBackup, passphrase);

        assertNotNull(record);
        assertEquals(CloudSyncStatus.PENDING, record.status());
        assertEquals("backup_2026_06_15.db", record.backupFileName());

        Path encryptedPath = Path.of(record.encryptedFilePath());
        assertTrue(Files.exists(encryptedPath));
        assertNotEquals(originalContent, Files.readString(encryptedPath, StandardCharsets.ISO_8859_1));

        // Decrypt to new file
        Path decryptedPath = tempDir.resolve("restored_backup.db");
        syncService.decryptBackup(encryptedPath, decryptedPath, passphrase);

        assertTrue(Files.exists(decryptedPath));
        assertEquals(originalContent, Files.readString(decryptedPath, StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("Should fail decryption when supplied with incorrect passphrase")
    void testDecryptionWithWrongPassphraseFails() throws Exception {
        Path originalBackup = tempDir.resolve("backup_test.db");
        Files.writeString(originalBackup, "Secret shop financial records");

        CloudSyncRecord record = syncService.enqueueBackupForUpload(originalBackup, "CorrectPassword123!");
        Path encryptedPath = Path.of(record.encryptedFilePath());

        Path decryptedPath = tempDir.resolve("failed_restore.db");
        assertThrows(Exception.class, () ->
                syncService.decryptBackup(encryptedPath, decryptedPath, "WrongPassword456!")
        );
    }

    @Test
    @DisplayName("Should handle offline state when cloud storage client is unavailable")
    void testSyncWhenClientUnavailable() throws IOException {
        Path dummy = tempDir.resolve("backup_offline.db");
        Files.writeString(dummy, "test");
        syncService.enqueueBackupForUpload(dummy, "Passphrase123!");

        assertEquals(1, syncService.getPendingCount());

        CloudStorageClient offlineClient = new CloudStorageClient() {
            @Override
            public boolean isAvailable() { return false; }
            @Override
            public boolean upload(String remoteFileName, Path localFile) { return false; }
            @Override
            public boolean download(String remoteFileName, Path destination) { return false; }
        };

        int synced = syncService.syncPendingBackups(offlineClient);
        assertEquals(0, synced);
        assertEquals(1, syncService.getPendingCount());
    }

    @Test
    @DisplayName("Should upload pending backups when client is available and cleanup spool")
    void testSuccessfulCloudSync() throws IOException {
        Path dummy = tempDir.resolve("backup_online.db");
        Files.writeString(dummy, "online test data");
        CloudSyncRecord record = syncService.enqueueBackupForUpload(dummy, "Passphrase123!");

        AtomicBoolean uploadCalled = new AtomicBoolean(false);
        CloudStorageClient onlineClient = new CloudStorageClient() {
            @Override
            public boolean isAvailable() { return true; }
            @Override
            public boolean upload(String remoteFileName, Path localFile) {
                uploadCalled.set(true);
                assertTrue(remoteFileName.endsWith(".enc"));
                return true;
            }
            @Override
            public boolean download(String remoteFileName, Path destination) { return true; }
        };

        int synced = syncService.syncPendingBackups(onlineClient);
        assertEquals(1, synced);
        assertTrue(uploadCalled.get());
        assertEquals(0, syncService.getPendingCount());

        List<CloudSyncRecord> history = syncService.getSyncHistory(10);
        assertEquals(1, history.size());
        assertEquals(CloudSyncStatus.UPLOADED, history.getFirst().status());
        assertNotNull(history.getFirst().uploadedAt());

        // Verify local spooled file cleaned up
        assertFalse(Files.exists(Path.of(record.encryptedFilePath())));
    }

    @Test
    @DisplayName("Should increment retry attempts on upload failure")
    void testSyncRetryOnFailure() throws IOException {
        Path dummy = tempDir.resolve("backup_fail.db");
        Files.writeString(dummy, "fail test data");
        syncService.enqueueBackupForUpload(dummy, "Passphrase123!");

        CloudStorageClient failingClient = new CloudStorageClient() {
            @Override
            public boolean isAvailable() { return true; }
            @Override
            public boolean upload(String remoteFileName, Path localFile) throws IOException {
                throw new IOException("Connection reset by peer: S3 unreachable");
            }
            @Override
            public boolean download(String remoteFileName, Path destination) { return false; }
        };

        int synced = syncService.syncPendingBackups(failingClient);
        assertEquals(0, synced);

        List<CloudSyncRecord> history = syncService.getSyncHistory(10);
        assertEquals(1, history.size());
        assertEquals(1, history.getFirst().attempts());
        assertTrue(history.getFirst().lastError().contains("Connection reset"));
        assertEquals(CloudSyncStatus.PENDING, history.getFirst().status());
    }
}
