package com.shopbilling.service;

import com.shopbilling.db.ConnectionFactory;
import com.shopbilling.db.DatabaseConfig;
import com.shopbilling.db.FlywayMigrator;
import com.shopbilling.model.DatabaseHealthStatus;
import com.shopbilling.model.WalCheckpointMode;
import com.shopbilling.model.WalCheckpointResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("DatabaseMaintenanceService Tests (SQLite Integrity, WAL Checkpoint & Health Diagnostics)")
class DatabaseMaintenanceServiceTest {

    @TempDir
    Path tempDir;

    private ConnectionFactory connectionFactory;
    private Path dbFile;
    private DatabaseMaintenanceService maintenanceService;

    @BeforeEach
    void setUp() throws IOException {
        dbFile = tempDir.resolve("db_maintenance_test.db");
        DatabaseConfig config = DatabaseConfig.forFile(dbFile);
        connectionFactory = new ConnectionFactory(config);
        new FlywayMigrator(connectionFactory).migrate();

        maintenanceService = new DatabaseMaintenanceService(connectionFactory, dbFile);
    }

    @Test
    @DisplayName("checkDatabaseHealth reports clean integrity, valid FKs, and accurate storage telemetry")
    void testCheckDatabaseHealth() {
        DatabaseHealthStatus health = maintenanceService.checkDatabaseHealth();

        assertNotNull(health);
        assertTrue(health.isHealthy(), "Fresh migrated database should be healthy");
        assertTrue(health.isIntegrityOk());
        assertTrue(health.isForeignKeysOk());
        assertTrue(health.integrityErrors().isEmpty());
        assertTrue(health.foreignKeyViolations().isEmpty());

        assertTrue(health.totalPageCount() > 0, "Page count should be positive");
        assertTrue(health.pageSizeBytes() >= 512, "Page size should be valid");
        assertTrue(health.dbFileSizeBytes() > 0, "DB file size should be positive");
        assertTrue(health.diskFreeSpaceBytes() > 0, "Disk space should be positive");
        assertNotNull(health.sqliteVersion());
        assertFalse(health.sqliteVersion().isBlank());
    }

    @Test
    @DisplayName("checkpointWal executes TRUNCATE, PASSIVE, and FULL checkpoint modes successfully")
    void testCheckpointWal() {
        WalCheckpointResult resTruncate = maintenanceService.checkpointWal(WalCheckpointMode.TRUNCATE);
        assertNotNull(resTruncate);
        assertTrue(resTruncate.isSuccessful());
        assertEquals(WalCheckpointMode.TRUNCATE, resTruncate.mode());

        WalCheckpointResult resPassive = maintenanceService.checkpointWal(WalCheckpointMode.PASSIVE);
        assertNotNull(resPassive);
        assertTrue(resPassive.isSuccessful());

        WalCheckpointResult resFull = maintenanceService.checkpointWal(WalCheckpointMode.FULL);
        assertNotNull(resFull);
        assertTrue(resFull.isSuccessful());
    }

    @Test
    @DisplayName("optimizeQueryPlanner and vacuumDatabase run safely without errors")
    void testOptimizeAndVacuum() {
        assertDoesNotThrow(() -> maintenanceService.optimizeQueryPlanner());
        assertDoesNotThrow(() -> maintenanceService.vacuumDatabase());

        DatabaseHealthStatus health = maintenanceService.checkDatabaseHealth();
        assertTrue(health.isHealthy());
    }

    @Test
    @DisplayName("checkDatabaseHealth detects foreign key violations when inconsistent records exist")
    void testDetectForeignKeyViolations() throws Exception {
        // Temporarily insert a disconnected record with foreign keys disabled
        try (Connection conn = connectionFactory.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA foreign_keys = OFF;");
            // Insert product pointing to non-existent brand_id=9999 and category_id=9999
            stmt.execute("INSERT INTO products (brand_id, category_id, name, unit_code) " +
                    "VALUES (9999, 9999, 'Broken FK Product', 'PIECE');");
        }

        DatabaseHealthStatus health = maintenanceService.checkDatabaseHealth();
        assertFalse(health.isForeignKeysOk(), "Should flag foreign key violations");
        assertFalse(health.isHealthy());
        assertFalse(health.foreignKeyViolations().isEmpty());
        assertTrue(health.foreignKeyViolations().getFirst().contains("products"));
    }
}
