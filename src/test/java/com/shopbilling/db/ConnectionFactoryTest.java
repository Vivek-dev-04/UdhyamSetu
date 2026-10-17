package com.shopbilling.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectionFactoryTest {

    @Test
    @DisplayName("ConnectionFactory enforces WAL mode and Foreign Keys on every connection")
    void shouldEnforceWalAndForeignKeys(@TempDir Path tempDir) throws SQLException {
        Path dbFile = tempDir.resolve("test_shop.db");
        DatabaseConfig config = DatabaseConfig.forFile(dbFile);
        ConnectionFactory factory = new ConnectionFactory(config);

        try (Connection conn = factory.getConnection()) {
            // Verify foreign keys pragma is ON
            assertTrue(factory.verifyForeignKeysEnabled(conn), "Foreign keys must be enabled");

            // Verify journal mode is WAL
            String journalMode = factory.getActiveJournalMode(conn);
            assertEquals("wal", journalMode, "Journal mode must be WAL");
        }
    }

    @Test
    @DisplayName("Connection actively blocks foreign key constraint violations")
    void shouldBlockForeignKeyViolations(@TempDir Path tempDir) throws SQLException {
        Path dbFile = tempDir.resolve("fk_test.db");
        DatabaseConfig config = DatabaseConfig.forFile(dbFile);
        ConnectionFactory factory = new ConnectionFactory(config);

        try (Connection conn = factory.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE parent (id INTEGER PRIMARY KEY, name TEXT);");
            stmt.execute("CREATE TABLE child (id INTEGER PRIMARY KEY, parent_id INTEGER, FOREIGN KEY(parent_id) REFERENCES parent(id));");

            // Inserting child with nonexistent parent must throw SQLException
            assertThrows(SQLException.class, () ->
                    stmt.execute("INSERT INTO child (id, parent_id) VALUES (1, 999);")
            );
        }
    }
}
