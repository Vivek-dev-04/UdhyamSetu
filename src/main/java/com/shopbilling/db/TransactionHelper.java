package com.shopbilling.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

/**
 * Transaction helper executing database operations inside an atomic transaction.
 * (PRD Section 8, Rule 3: Operations are saved in one atomic transaction;
 * failure rolls back everything).
 */
public class TransactionHelper {

    private static final Logger logger = LoggerFactory.getLogger(TransactionHelper.class);

    private final ConnectionFactory connectionFactory;

    public TransactionHelper(ConnectionFactory connectionFactory) {
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "ConnectionFactory cannot be null");
    }

    @FunctionalInterface
    public interface TransactionCallback<T> {
        T doInTransaction(Connection connection) throws Exception;
    }

    @FunctionalInterface
    public interface TransactionVoidCallback {
        void doInTransaction(Connection connection) throws Exception;
    }

    /**
     * Executes a callback within a managed transaction with automatic commit/rollback.
     * 
     * @param callback The transactional work to perform with the active Connection.
     * @return The result produced by the callback.
     * @throws TransactionException if the transaction fails and is rolled back.
     */
    public <T> T execute(TransactionCallback<T> callback) {
        Objects.requireNonNull(callback, "Transaction callback cannot be null");
        try (Connection conn = connectionFactory.getConnection()) {
            boolean originalAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                T result = callback.doInTransaction(conn);
                conn.commit();
                return result;
            } catch (Exception e) {
                try {
                    conn.rollback();
                    logger.warn("Transaction rolled back due to error: {}", e.getMessage());
                } catch (SQLException rollbackEx) {
                    logger.error("Failed to rollback transaction", rollbackEx);
                    e.addSuppressed(rollbackEx);
                }
                if (e instanceof RuntimeException re) {
                    throw re;
                }
                throw new TransactionException("Transaction failed and was rolled back: " + e.getMessage(), e);
            } finally {
                try {
                    conn.setAutoCommit(originalAutoCommit);
                } catch (SQLException ex) {
                    logger.debug("Could not reset auto-commit state", ex);
                }
            }
        } catch (SQLException e) {
            throw new DatabaseException("Database error during transaction execution: " + e.getMessage(), e);
        }
    }

    /**
     * Executes a void callback within a managed transaction.
     */
    public void executeVoid(TransactionVoidCallback callback) {
        Objects.requireNonNull(callback, "Callback cannot be null");
        execute(conn -> {
            callback.doInTransaction(conn);
            return null;
        });
    }

    /**
     * Executes read-only queries with a managed connection.
     */
    public <T> T executeRead(TransactionCallback<T> callback) {
        Objects.requireNonNull(callback, "Read callback cannot be null");
        try (Connection conn = connectionFactory.getConnection()) {
            return callback.doInTransaction(conn);
        } catch (Exception e) {
            if (e instanceof RuntimeException re) {
                throw re;
            }
            throw new DatabaseException("Read query execution failed: " + e.getMessage(), e);
        }
    }
}
