package com.shopbilling.db;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TransactionHelperTest {

    private ConnectionFactory connectionFactory;
    private TransactionHelper transactionHelper;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        Path dbFile = tempDir.resolve("tx_test.db");
        DatabaseConfig config = DatabaseConfig.forFile(dbFile);
        connectionFactory = new ConnectionFactory(config);
        transactionHelper = new TransactionHelper(connectionFactory);

        // Setup test schema
        transactionHelper.executeVoid(conn -> {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE TABLE orders (id INTEGER PRIMARY KEY, customer_name TEXT NOT NULL);");
                stmt.execute("CREATE TABLE order_items (id INTEGER PRIMARY KEY, order_id INTEGER NOT NULL, item_name TEXT NOT NULL, FOREIGN KEY(order_id) REFERENCES orders(id));");
            }
        });
    }

    @Test
    @DisplayName("Successful transaction commits data permanently")
    void shouldCommitOnSuccess() {
        transactionHelper.executeVoid(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO orders (id, customer_name) VALUES (?, ?);")) {
                ps.setInt(1, 101);
                ps.setString(2, "Ramesh Kumar");
                ps.executeUpdate();
            }
        });

        int orderCount = transactionHelper.executeRead(conn -> {
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM orders;")) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        });

        assertEquals(1, orderCount, "Committed order should be present in database");
    }

    @Test
    @DisplayName("Transaction rolls back completely on RuntimeException")
    void shouldRollbackOnRuntimeException() {
        assertThrows(IllegalStateException.class, () ->
                transactionHelper.executeVoid(conn -> {
                    try (PreparedStatement ps = conn.prepareStatement("INSERT INTO orders (id, customer_name) VALUES (?, ?);")) {
                        ps.setInt(1, 102);
                        ps.setString(2, "Suresh");
                        ps.executeUpdate();
                    }
                    throw new IllegalStateException("Forced failure during processing");
                })
        );

        int count = getOrderCount();
        assertEquals(0, count, "No order should be persisted after rollback");
    }

    @Test
    @DisplayName("Transaction rolls back completely on checked Exception and wraps in TransactionException")
    void shouldRollbackOnCheckedException() {
        assertThrows(TransactionException.class, () ->
                transactionHelper.executeVoid(conn -> {
                    try (PreparedStatement ps = conn.prepareStatement("INSERT INTO orders (id, customer_name) VALUES (?, ?);")) {
                        ps.setInt(1, 103);
                        ps.setString(2, "Dinesh");
                        ps.executeUpdate();
                    }
                    throw new Exception("Checked exception during processing");
                })
        );

        int count = getOrderCount();
        assertEquals(0, count, "No order should be persisted after rollback");
    }

    @Test
    @DisplayName("Multi-table atomic rollback: rolls back both parent and child on failure")
    void shouldRollbackMultiTableTransaction() {
        assertThrows(RuntimeException.class, () ->
                transactionHelper.executeVoid(conn -> {
                    // 1. Insert order
                    try (PreparedStatement ps = conn.prepareStatement("INSERT INTO orders (id, customer_name) VALUES (?, ?);")) {
                        ps.setInt(1, 201);
                        ps.setString(2, "Electrician Rajesh");
                        ps.executeUpdate();
                    }

                    // 2. Insert item
                    try (PreparedStatement ps = conn.prepareStatement("INSERT INTO order_items (id, order_id, item_name) VALUES (?, ?, ?);")) {
                        ps.setInt(1, 501);
                        ps.setInt(2, 201);
                        ps.setString(3, "Havells 16A Switch");
                        ps.executeUpdate();
                    }

                    // 3. Simulate failure (e.g. stock deduction failure or payment mismatch)
                    throw new RuntimeException("Simulated stock deduction failure");
                })
        );

        int orders = getOrderCount();
        int items = transactionHelper.executeRead(conn -> {
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM order_items;")) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        });

        assertEquals(0, orders, "Parent order must be rolled back");
        assertEquals(0, items, "Child order item must be rolled back");
    }

    private int getOrderCount() {
        return transactionHelper.executeRead(conn -> {
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM orders;")) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        });
    }
}
