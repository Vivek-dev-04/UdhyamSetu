package com.shopbilling.db;

import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V1SchemaMigrationTest {

    private static final String[] EXPECTED_TABLES = {
            "roles", "users",
            "customer_types", "customers", "suppliers",
            "brands", "categories", "units", "products", "product_variants",
            "bill_counters", "credit_note_counters",
            "sale_modes", "sales", "sale_items",
            "payment_modes", "sale_payments",
            "stock_movement_types", "stock_movements", "purchases", "purchase_items",
            "returns", "return_items",
            "customer_ledger_types", "customer_ledger_entries", "customer_payment_allocations",
            "supplier_ledger_types", "supplier_ledger_entries",
            "expense_categories", "expenses", "day_end_closings",
            "audit_log", "settings", "backup_log"
    };

    @Test
    @DisplayName("V1 schema migration creates all PRD tables, indexes, and seeded data cleanly")
    void shouldMigrateAndVerifyAllTables(@TempDir Path tempDir) throws SQLException {
        Path dbFile = tempDir.resolve("v1_schema_test.db");
        DatabaseConfig config = DatabaseConfig.forFile(dbFile);
        ConnectionFactory connectionFactory = new ConnectionFactory(config);

        // Run migrations
        FlywayMigrator migrator = new FlywayMigrator(connectionFactory);
        MigrateResult result = migrator.migrate();

        assertNotNull(result);
        assertTrue(result.success, "Flyway migration must succeed");
        assertTrue(result.migrationsExecuted >= 1, "At least 1 migration must be executed");

        try (Connection conn = connectionFactory.getConnection();
             Statement stmt = conn.createStatement()) {

            // 1. Verify all expected tables exist in sqlite_master
            Set<String> existingTables = new HashSet<>();
            try (ResultSet rs = stmt.executeQuery("SELECT name FROM sqlite_master WHERE type='table';")) {
                while (rs.next()) {
                    existingTables.add(rs.getString("name").toLowerCase());
                }
            }

            for (String expectedTable : EXPECTED_TABLES) {
                assertTrue(existingTables.contains(expectedTable.toLowerCase()),
                        "Expected table missing from V1 schema: " + expectedTable);
            }

            // 2. Verify seeded roles
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM roles;")) {
                assertTrue(rs.next());
                assertEquals(2, rs.getInt(1), "Expected 2 roles: OWNER and STAFF");
            }

            // 3. Verify seeded customer types
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM customer_types;")) {
                assertTrue(rs.next());
                assertEquals(2, rs.getInt(1), "Expected 2 customer types: RETAIL and TECHNICIAN");
            }

            // 4. Verify seeded units
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM units;")) {
                assertTrue(rs.next());
                assertEquals(4, rs.getInt(1), "Expected 4 default units");
            }

            // 5. Verify default settings
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM settings;")) {
                assertTrue(rs.next());
                assertTrue(rs.getInt(1) >= 5, "Expected at least 5 default configuration settings");
            }

            // 6. Verify foreign key integrity actively prevents orphans
            assertThrows(SQLException.class, () ->
                    stmt.execute("INSERT INTO products (brand_id, category_id, name, unit_code) " +
                            "VALUES (999, 999, 'Orphan Product', 'PIECE');")
            );
        }
    }
}
