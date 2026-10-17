package com.shopbilling.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataDirectoryHandlerTest {

    @AfterEach
    void tearDown() {
        System.clearProperty(DataDirectoryHandler.PROPERTY_DATA_DIR);
    }

    @Test
    @DisplayName("Should resolve and create directories when ensureDirectoriesExist is called")
    void shouldCreateDirectories(@TempDir Path tempDir) {
        Path customData = tempDir.resolve("my-shop-data");
        DataDirectoryHandler handler = new DataDirectoryHandler(customData);

        assertEquals(customData.toAbsolutePath().normalize(), handler.getDataDirectory());
        assertEquals(customData.resolve("shop_billing.db").toAbsolutePath().normalize(), handler.getDatabasePath());
        assertEquals(customData.resolve("backups").toAbsolutePath().normalize(), handler.getBackupDirectory());

        handler.ensureDirectoriesExist();
        assertTrue(Files.exists(handler.getDataDirectory()));
        assertTrue(Files.exists(handler.getBackupDirectory()));
    }

    @Test
    @DisplayName("Should respect system property override for data directory")
    void shouldRespectSystemPropertyOverride(@TempDir Path tempDir) {
        System.setProperty(DataDirectoryHandler.PROPERTY_DATA_DIR, tempDir.toString());
        Path resolved = DataDirectoryHandler.resolveDefaultDataDirectory();
        assertEquals(tempDir.toAbsolutePath().normalize(), resolved.toAbsolutePath().normalize());
    }

    @Test
    @DisplayName("Default data directory should not be null or empty")
    void shouldHaveValidDefaultPath() {
        Path defaultPath = DataDirectoryHandler.resolveDefaultDataDirectory();
        assertNotNull(defaultPath);
        assertTrue(defaultPath.toString().contains("ShopBilling") || defaultPath.toString().contains(".shopbilling"));
    }
}
