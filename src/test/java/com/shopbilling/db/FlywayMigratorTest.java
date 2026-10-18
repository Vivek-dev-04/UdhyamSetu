package com.shopbilling.db;

import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class FlywayMigratorTest {

    @Test
    @DisplayName("FlywayMigrator applies migrations cleanly to a SQLite database")
    void shouldApplyMigrations(@TempDir Path tempDir) throws Exception {
        Path dbFile = tempDir.resolve("flyway_test.db");
        DatabaseConfig config = DatabaseConfig.forFile(dbFile);
        ConnectionFactory connectionFactory = new ConnectionFactory(config);

        FlywayMigrator migrator = new FlywayMigrator(connectionFactory, "classpath:db/test_migration");
        MigrateResult result = migrator.migrate();

        assertNotNull(result);
        assertTrue(result.success, "Migration must be successful");
        assertEquals(1, result.migrationsExecuted, "Should execute exactly 1 test migration");

        // Verify test_sample table created and accessible
        try (Connection conn = connectionFactory.getConnection();
             Statement stmt = conn.createStatement()) {

            // Check flyway_schema_history table exists
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM flyway_schema_history;")) {
                assertTrue(rs.next());
                assertTrue(rs.getInt(1) >= 1);
            }

            // Insert into the created table
            stmt.execute("INSERT INTO test_sample (name) VALUES ('Test Item 1');");
            try (ResultSet rs = stmt.executeQuery("SELECT name FROM test_sample WHERE id = 1;")) {
                assertTrue(rs.next());
                assertEquals("Test Item 1", rs.getString("name"));
            }
        }

        // Verify info API
        MigrationInfo[] info = migrator.getMigrationInfo();
        assertEquals(1, info.length);
        assertEquals("1", info[0].getVersion().getVersion());
    }

    @Test
    @DisplayName("FlywayMigrator triggers pre-migration backup hook before applying upgrades (FR-49)")
    void shouldTriggerPreMigrationBackupOnUpgrade(@TempDir Path tempDir) throws Exception {
        Path dbFile = tempDir.resolve("upgrade_test.db");
        Path migDir = tempDir.resolve("migrations");
        Files.createDirectories(migDir);

        // Step 1: Create initial V1 schema
        Files.writeString(migDir.resolve("V1__initial.sql"), "CREATE TABLE t1 (id INTEGER PRIMARY KEY, col1 TEXT);");

        DatabaseConfig config = DatabaseConfig.forFile(dbFile);
        ConnectionFactory connectionFactory = new ConnectionFactory(config);

        String fsLocation = "filesystem:" + migDir.toAbsolutePath().toString();

        AtomicBoolean backupHookInvoked = new AtomicBoolean(false);
        PreMigrationBackupHook hook = () -> backupHookInvoked.set(true);

        FlywayMigrator initialMigrator = new FlywayMigrator(connectionFactory, fsLocation, hook);
        MigrateResult r1 = initialMigrator.migrate();
        assertEquals(1, r1.migrationsExecuted);
        assertFalse(backupHookInvoked.get(), "Hook should NOT trigger on initial clean migration");

        // Step 2: Add V2 upgrade script
        Files.writeString(migDir.resolve("V2__add_column.sql"), "ALTER TABLE t1 ADD COLUMN col2 TEXT;");

        FlywayMigrator upgradeMigrator = new FlywayMigrator(connectionFactory, fsLocation, hook);
        MigrateResult r2 = upgradeMigrator.migrate();
        assertEquals(1, r2.migrationsExecuted);
        assertTrue(backupHookInvoked.get(), "Hook MUST trigger before executing pending upgrade on existing DB (FR-49)");

        // Verify col2 exists
        try (Connection conn = connectionFactory.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO t1 (id, col1, col2) VALUES (1, 'val1', 'val2');");
            try (ResultSet rs = stmt.executeQuery("SELECT col2 FROM t1 WHERE id = 1;")) {
                assertTrue(rs.next());
                assertEquals("val2", rs.getString("col2"));
            }
        }
    }

    @Test
    @DisplayName("FlywayMigrator aborts migration and does not apply upgrades if backup hook fails (FR-49)")
    void shouldAbortMigrationIfBackupFails(@TempDir Path tempDir) throws Exception {
        Path dbFile = tempDir.resolve("abort_test.db");
        Path migDir = tempDir.resolve("migrations");
        Files.createDirectories(migDir);

        Files.writeString(migDir.resolve("V1__initial.sql"), "CREATE TABLE t2 (id INTEGER PRIMARY KEY);");

        DatabaseConfig config = DatabaseConfig.forFile(dbFile);
        ConnectionFactory connectionFactory = new ConnectionFactory(config);
        String fsLocation = "filesystem:" + migDir.toAbsolutePath().toString();

        FlywayMigrator initialMigrator = new FlywayMigrator(connectionFactory, fsLocation);
        initialMigrator.migrate();

        // Add V2
        Files.writeString(migDir.resolve("V2__forbidden.sql"), "CREATE TABLE t3 (id INTEGER PRIMARY KEY);");

        PreMigrationBackupHook failingHook = () -> {
            throw new IOException("Simulated disk error during backup");
        };

        FlywayMigrator failingMigrator = new FlywayMigrator(connectionFactory, fsLocation, failingHook);
        assertThrows(DatabaseException.class, failingMigrator::migrate);

        // Verify t3 was NOT created
        try (Connection conn = connectionFactory.getConnection();
             Statement stmt = conn.createStatement()) {
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='t3';")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1), "t3 should not exist because migration was aborted");
            }
        }
    }
}
