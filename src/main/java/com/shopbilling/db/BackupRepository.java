package com.shopbilling.db;

import com.shopbilling.model.BackupRecord;
import com.shopbilling.model.BackupType;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Parameterized SQL repository for database backup history and status tracking (PRD Section 7.11, FR-46, FR-49).
 */
public class BackupRepository {

    public BackupRecord insert(Connection conn,
                               String backupFilePath,
                               BackupType backupType,
                               long fileSizeBytes,
                               String status,
                               String errorMessage) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        Objects.requireNonNull(backupFilePath, "Backup file path cannot be null");
        Objects.requireNonNull(backupType, "Backup type cannot be null");
        Objects.requireNonNull(status, "Status cannot be null");

        String sql = "INSERT INTO backup_log (backup_file_path, backup_type, file_size_bytes, status, error_message) " +
                "VALUES (?, ?, ?, ?, ?);";

        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, backupFilePath.trim());
            ps.setString(2, backupType.name());
            ps.setLong(3, fileSizeBytes);
            ps.setString(4, status.trim().toUpperCase());
            ps.setString(5, errorMessage != null ? errorMessage.trim() : null);

            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    long id = rs.getLong(1);
                    return new BackupRecord(
                            id,
                            backupFilePath.trim(),
                            backupType,
                            fileSizeBytes,
                            status.trim().toUpperCase(),
                            errorMessage != null ? errorMessage.trim() : null,
                            null
                    );
                }
            }
        }
        throw new DatabaseException("Failed to insert backup record");
    }

    public Optional<BackupRecord> getLastSuccessfulBackup(Connection conn) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        String sql = "SELECT id, backup_file_path, backup_type, file_size_bytes, status, error_message, created_at " +
                "FROM backup_log WHERE status = 'SUCCESS' ORDER BY id DESC LIMIT 1;";

        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return Optional.of(mapRow(rs));
            }
        }
        return Optional.empty();
    }

    public List<BackupRecord> getRecentBackups(Connection conn, int limit) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        String sql = "SELECT id, backup_file_path, backup_type, file_size_bytes, status, error_message, created_at " +
                "FROM backup_log ORDER BY id DESC LIMIT ?;";

        List<BackupRecord> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapRow(rs));
                }
            }
        }
        return list;
    }

    private BackupRecord mapRow(ResultSet rs) throws SQLException {
        return new BackupRecord(
                rs.getLong("id"),
                rs.getString("backup_file_path"),
                BackupType.valueOf(rs.getString("backup_type")),
                rs.getLong("file_size_bytes"),
                rs.getString("status"),
                rs.getString("error_message"),
                rs.getString("created_at")
        );
    }
}
