package com.shopbilling.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;

/**
 * Factory for creating and verifying SQLite connections with WAL mode and foreign keys enabled.
 * (PRD Section 10 & Hard Rule: WAL mode and foreign keys enabled on every connection).
 */
public class ConnectionFactory {

    private static final Logger logger = LoggerFactory.getLogger(ConnectionFactory.class);

    private final DatabaseConfig databaseConfig;
    private final SQLiteDataSource dataSource;

    public ConnectionFactory(DatabaseConfig databaseConfig) {
        this.databaseConfig = Objects.requireNonNull(databaseConfig, "DatabaseConfig cannot be null");
        this.dataSource = createDataSource(databaseConfig);
    }

    private static SQLiteDataSource createDataSource(DatabaseConfig config) {
        SQLiteConfig sqliteConfig = config.toSQLiteConfig();
        SQLiteDataSource ds = new SQLiteDataSource(sqliteConfig);
        ds.setUrl(config.getJdbcUrl());
        return ds;
    }

    /**
     * Gets a new SQLite database connection with WAL mode and foreign keys enforced.
     */
    public Connection getConnection() throws SQLException {
        Connection conn = dataSource.getConnection();
        applyPragmas(conn);
        return conn;
    }

    /**
     * Applies and verifies the required SQLite PRAGMAs on every connection.
     */
    private void applyPragmas(Connection connection) throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            if (databaseConfig.isEnforceForeignKeys()) {
                stmt.execute("PRAGMA foreign_keys = ON;");
            }
            if (databaseConfig.getJournalMode() == SQLiteConfig.JournalMode.WAL) {
                stmt.execute("PRAGMA journal_mode = WAL;");
            }
        }
    }

    /**
     * Verifies that foreign keys are actively enforced on the connection.
     */
    public boolean verifyForeignKeysEnabled(Connection connection) throws SQLException {
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA foreign_keys;")) {
            if (rs.next()) {
                return rs.getInt(1) == 1;
            }
            return false;
        }
    }

    /**
     * Returns the active journal mode on the connection.
     */
    public String getActiveJournalMode(Connection connection) throws SQLException {
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA journal_mode;")) {
            if (rs.next()) {
                return rs.getString(1).toLowerCase();
            }
            return "unknown";
        }
    }

    public DataSource getDataSource() {
        return dataSource;
    }

    public DatabaseConfig getDatabaseConfig() {
        return databaseConfig;
    }
}
