package com.shopbilling.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;

/**
 * Handles the configuration, resolution, and directory creation for
 * the application's SQLite database and backup files.
 * 
 * Default directory on Windows: C:\ProgramData\ShopBilling\data
 * Default on Linux/Unix: ~/.shopbilling/data
 * Configurable via system property 'shopbilling.data.dir' or env var 'SHOP_BILLING_DATA_DIR'.
 */
public class DataDirectoryHandler {

    private static final Logger logger = LoggerFactory.getLogger(DataDirectoryHandler.class);

    public static final String PROPERTY_DATA_DIR = "shopbilling.data.dir";
    public static final String ENV_DATA_DIR = "SHOP_BILLING_DATA_DIR";
    public static final String DEFAULT_DB_FILENAME = "shop_billing.db";
    public static final String DEFAULT_BACKUP_SUBDIR = "backups";

    private final Path dataDirectory;

    public DataDirectoryHandler() {
        this(resolveDefaultDataDirectory());
    }

    public DataDirectoryHandler(Path dataDirectory) {
        this.dataDirectory = Objects.requireNonNull(dataDirectory, "Data directory cannot be null").toAbsolutePath().normalize();
    }

    /**
     * Resolves the default data directory according to OS and environment variables.
     */
    public static Path resolveDefaultDataDirectory() {
        // 1. Explicit system property
        String sysProp = System.getProperty(PROPERTY_DATA_DIR);
        if (sysProp != null && !sysProp.trim().isEmpty()) {
            return Paths.get(sysProp.trim());
        }

        // 2. Explicit environment variable
        String envVar = System.getenv(ENV_DATA_DIR);
        if (envVar != null && !envVar.trim().isEmpty()) {
            return Paths.get(envVar.trim());
        }

        // 3. OS-specific default
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            String programData = System.getenv("ProgramData");
            if (programData != null && !programData.trim().isEmpty()) {
                return Paths.get(programData, "ShopBilling", "data");
            }
            return Paths.get("C:", "ProgramData", "ShopBilling", "data");
        } else {
            String userHome = System.getProperty("user.home", ".");
            return Paths.get(userHome, ".shopbilling", "data");
        }
    }

    /**
     * Ensures that the data directory and backup subdirectory exist on the filesystem.
     */
    public void ensureDirectoriesExist() {
        try {
            Files.createDirectories(dataDirectory);
            Files.createDirectories(getBackupDirectory());
            logger.info("Data directory verified at: {}", dataDirectory);
        } catch (IOException e) {
            throw new DatabaseException("Failed to initialize application data directories at: " + dataDirectory, e);
        }
    }

    public Path getDataDirectory() {
        return dataDirectory;
    }

    public Path getDatabasePath() {
        return dataDirectory.resolve(DEFAULT_DB_FILENAME);
    }

    public Path getBackupDirectory() {
        return dataDirectory.resolve(DEFAULT_BACKUP_SUBDIR);
    }
}
