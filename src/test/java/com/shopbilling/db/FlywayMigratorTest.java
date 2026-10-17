package com.shopbilling.db;

import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
