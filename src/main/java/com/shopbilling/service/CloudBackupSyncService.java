package com.shopbilling.service;

import com.shopbilling.db.CloudSyncRepository;
import com.shopbilling.db.TransactionHelper;
import com.shopbilling.model.CloudSyncRecord;
import com.shopbilling.model.CloudSyncStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;

/**
 * Service managing offline-first, AES-256-GCM encrypted cloud backups (PRD Section 7.11 FR-47, Section 14).
 * Pure service: strictly contains NO JavaFX imports.
 */
public class CloudBackupSyncService {

    private static final Logger logger = LoggerFactory.getLogger(CloudBackupSyncService.class);

    private static final String ENCRYPTION_ALGO = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128; // in bits
    private static final int GCM_IV_LENGTH = 12;   // in bytes
    private static final int SALT_LENGTH = 16;     // in bytes
    private static final int KEY_LENGTH = 256;     // in bits
    private static final int ITERATION_COUNT = 65536;

    private final TransactionHelper transactionHelper;
    private final CloudSyncRepository repository;
    private final Path spoolDirectory;
    private final SecureRandom secureRandom;
    private final IstClock clock;

    public CloudBackupSyncService(TransactionHelper transactionHelper,
                                  CloudSyncRepository repository,
                                  Path spoolDirectory,
                                  IstClock clock) {
        this.transactionHelper = Objects.requireNonNull(transactionHelper, "TransactionHelper cannot be null");
        this.repository = Objects.requireNonNull(repository, "CloudSyncRepository cannot be null");
        this.spoolDirectory = Objects.requireNonNull(spoolDirectory, "SpoolDirectory cannot be null");
        this.clock = Objects.requireNonNull(clock, "IstClock cannot be null");
        this.secureRandom = new SecureRandom();

        try {
            Files.createDirectories(spoolDirectory);
        } catch (IOException e) {
            logger.warn("Could not create cloud backup spool directory: {}", spoolDirectory, e);
        }
    }

    /**
     * Encrypts a local database backup using AES-256-GCM and queues it for remote cloud synchronization.
     * Offline-first: if internet is not available, the backup remains safely spooled in local storage.
     */
    public CloudSyncRecord enqueueBackupForUpload(Path backupFilePath, String passphrase) {
        Objects.requireNonNull(backupFilePath, "Backup file path cannot be null");
        if (passphrase == null || passphrase.trim().length() < 8) {
            throw new ValidationException("Encryption passphrase must be at least 8 characters long");
        }
        if (!Files.exists(backupFilePath)) {
            throw new ValidationException("Backup file does not exist: " + backupFilePath);
        }

        String fileName = backupFilePath.getFileName().toString();
        String encryptedFileName = fileName + ".enc";
        Path encryptedPath = spoolDirectory.resolve(encryptedFileName);

        try {
            encryptFile(backupFilePath, encryptedPath, passphrase);
        } catch (Exception e) {
            logger.error("Failed to encrypt backup file: {}", backupFilePath, e);
            throw new ValidationException("Failed to encrypt backup file: " + e.getMessage());
        }

        CloudSyncRecord initial = new CloudSyncRecord(
                null,
                fileName,
                encryptedPath.toAbsolutePath().toString(),
                CloudSyncStatus.PENDING,
                0,
                null,
                null,
                null
        );

        CloudSyncRecord created = transactionHelper.execute(conn -> repository.insert(conn, initial));
        logger.info("Enqueued encrypted cloud backup id={} file={}", created.id(), encryptedFileName);
        return created;
    }

    /**
     * Processes pending backups and uploads them to the remote cloud storage client.
     * Never blocks billing transactions.
     * @return count of successfully uploaded backups
     */
    public int syncPendingBackups(CloudStorageClient client) {
        Objects.requireNonNull(client, "CloudStorageClient cannot be null");
        if (!client.isAvailable()) {
            logger.debug("Cloud storage client is currently unavailable/offline. Skipping sync cycle.");
            return 0;
        }

        List<CloudSyncRecord> pending = transactionHelper.executeRead(conn -> repository.findPending(conn, 10));
        int successCount = 0;

        for (CloudSyncRecord item : pending) {
            Path encryptedFile = Path.of(item.encryptedFilePath());
            if (!Files.exists(encryptedFile)) {
                logger.error("Spooled encrypted file not found on disk: {}", encryptedFile);
                transactionHelper.executeVoid(conn ->
                        repository.updateStatus(conn, item.id(), CloudSyncStatus.FAILED, item.attempts() + 1, "Spooled file missing on disk", null)
                );
                continue;
            }

            try {
                boolean uploaded = client.upload(item.backupFileName() + ".enc", encryptedFile);
                if (uploaded) {
                    String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                    transactionHelper.executeVoid(conn ->
                            repository.updateStatus(conn, item.id(), CloudSyncStatus.UPLOADED, item.attempts() + 1, null, now)
                    );
                    // Cleanup spooled encrypted file after successful upload to save local disk space
                    try {
                        Files.deleteIfExists(encryptedFile);
                    } catch (IOException ignored) {}

                    successCount++;
                    logger.info("Successfully synced backup to cloud: {}", item.backupFileName());
                } else {
                    int nextAttempts = item.attempts() + 1;
                    CloudSyncStatus nextStatus = nextAttempts >= 5 ? CloudSyncStatus.FAILED : CloudSyncStatus.PENDING;
                    transactionHelper.executeVoid(conn ->
                            repository.updateStatus(conn, item.id(), nextStatus, nextAttempts, "Upload rejected by remote storage", null)
                    );
                }
            } catch (Exception e) {
                logger.warn("Failed to upload backup id={} to cloud: {}", item.id(), e.getMessage());
                int nextAttempts = item.attempts() + 1;
                CloudSyncStatus nextStatus = nextAttempts >= 5 ? CloudSyncStatus.FAILED : CloudSyncStatus.PENDING;
                transactionHelper.executeVoid(conn ->
                        repository.updateStatus(conn, item.id(), nextStatus, nextAttempts, e.getMessage(), null)
                );
            }
        }

        return successCount;
    }

    /**
     * Decrypts an encrypted backup file using AES-256-GCM.
     */
    public void decryptBackup(Path encryptedFilePath, Path targetDecryptedPath, String passphrase) throws Exception {
        Objects.requireNonNull(encryptedFilePath, "Encrypted file path cannot be null");
        Objects.requireNonNull(targetDecryptedPath, "Target decrypted path cannot be null");
        if (passphrase == null || passphrase.trim().length() < 8) {
            throw new ValidationException("Encryption passphrase must be at least 8 characters long");
        }

        try (FileInputStream fis = new FileInputStream(encryptedFilePath.toFile())) {
            byte[] salt = new byte[SALT_LENGTH];
            if (fis.read(salt) != SALT_LENGTH) {
                throw new ValidationException("Corrupted encrypted backup file: invalid salt header");
            }

            byte[] iv = new byte[GCM_IV_LENGTH];
            if (fis.read(iv) != GCM_IV_LENGTH) {
                throw new ValidationException("Corrupted encrypted backup file: invalid IV header");
            }

            SecretKey key = deriveKey(passphrase, salt);
            Cipher cipher = Cipher.getInstance(ENCRYPTION_ALGO);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, key, spec);

            try (FileOutputStream fos = new FileOutputStream(targetDecryptedPath.toFile())) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = fis.read(buffer)) != -1) {
                    byte[] output = cipher.update(buffer, 0, bytesRead);
                    if (output != null) {
                        fos.write(output);
                    }
                }
                byte[] finalOutput = cipher.doFinal();
                if (finalOutput != null) {
                    fos.write(finalOutput);
                }
            }
        }
    }

    public int getPendingCount() {
        return transactionHelper.executeRead(repository::countPending);
    }

    public List<CloudSyncRecord> getSyncHistory(int limit) {
        return transactionHelper.executeRead(conn -> repository.getRecent(conn, limit));
    }

    private void encryptFile(Path inputPath, Path outputPath, String passphrase) throws Exception {
        byte[] salt = new byte[SALT_LENGTH];
        secureRandom.nextBytes(salt);

        byte[] iv = new byte[GCM_IV_LENGTH];
        secureRandom.nextBytes(iv);

        SecretKey key = deriveKey(passphrase, salt);
        Cipher cipher = Cipher.getInstance(ENCRYPTION_ALGO);
        GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        cipher.init(Cipher.ENCRYPT_MODE, key, spec);

        try (FileInputStream fis = new FileInputStream(inputPath.toFile());
             FileOutputStream fos = new FileOutputStream(outputPath.toFile())) {
            // Write Salt and IV headers
            fos.write(salt);
            fos.write(iv);

            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                byte[] output = cipher.update(buffer, 0, bytesRead);
                if (output != null) {
                    fos.write(output);
                }
            }
            byte[] finalOutput = cipher.doFinal();
            if (finalOutput != null) {
                fos.write(finalOutput);
            }
        }
    }

    private SecretKey deriveKey(String passphrase, byte[] salt) throws Exception {
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        KeySpec spec = new PBEKeySpec(passphrase.toCharArray(), salt, ITERATION_COUNT, KEY_LENGTH);
        SecretKey secretKey = factory.generateSecret(spec);
        return new SecretKeySpec(secretKey.getEncoded(), "AES");
    }
}
