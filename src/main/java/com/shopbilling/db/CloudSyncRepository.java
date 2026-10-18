package com.shopbilling.db;

import com.shopbilling.model.CloudSyncRecord;
import com.shopbilling.model.CloudSyncStatus;

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
 * Data access repository for cloud backup synchronization queue (PRD Section 7.11 FR-47).
 */
public class CloudSyncRepository {

    public CloudSyncRecord insert(Connection conn, CloudSyncRecord record) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        Objects.requireNonNull(record, "CloudSyncRecord cannot be null");

        String sql = "INSERT INTO cloud_sync_queue (backup_file_name, encrypted_file_path, status, attempts, last_error) " +
                "VALUES (?, ?, ?, ?, ?);";

        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, record.backupFileName());
            ps.setString(2, record.encryptedFilePath());
            ps.setString(3, record.status().name());
            ps.setInt(4, record.attempts());
            ps.setString(5, record.lastError());
            ps.executeUpdate();

            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    long generatedId = rs.getLong(1);
                    return findById(conn, generatedId)
                            .orElseThrow(() -> new DatabaseException("Failed to retrieve inserted cloud sync record"));
                }
            }
        }
        throw new DatabaseException("Insert failed, no ID returned");
    }

    public Optional<CloudSyncRecord> findById(Connection conn, long id) throws SQLException {
        String sql = "SELECT id, backup_file_name, encrypted_file_path, status, attempts, last_error, created_at, uploaded_at " +
                "FROM cloud_sync_queue WHERE id = ?;";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
        }
        return Optional.empty();
    }

    public List<CloudSyncRecord> findPending(Connection conn, int limit) throws SQLException {
        String sql = "SELECT id, backup_file_name, encrypted_file_path, status, attempts, last_error, created_at, uploaded_at " +
                "FROM cloud_sync_queue " +
                "WHERE status IN ('PENDING', 'FAILED') AND attempts < 5 " +
                "ORDER BY created_at ASC LIMIT ?;";

        List<CloudSyncRecord> list = new ArrayList<>();
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

    public void updateStatus(Connection conn, long id, CloudSyncStatus status, int attempts, String lastError, String uploadedAt) throws SQLException {
        String sql = "UPDATE cloud_sync_queue SET status = ?, attempts = ?, last_error = ?, uploaded_at = ? WHERE id = ?;";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status.name());
            ps.setInt(2, attempts);
            ps.setString(3, lastError);
            ps.setString(4, uploadedAt);
            ps.setLong(5, id);
            ps.executeUpdate();
        }
    }

    public int countPending(Connection conn) throws SQLException {
        String sql = "SELECT COUNT(*) FROM cloud_sync_queue WHERE status IN ('PENDING', 'FAILED') AND attempts < 5;";

        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return rs.getInt(1);
            }
        }
        return 0;
    }

    public List<CloudSyncRecord> getRecent(Connection conn, int limit) throws SQLException {
        String sql = "SELECT id, backup_file_name, encrypted_file_path, status, attempts, last_error, created_at, uploaded_at " +
                "FROM cloud_sync_queue " +
                "ORDER BY id DESC LIMIT ?;";

        List<CloudSyncRecord> list = new ArrayList<>();
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

    public void delete(Connection conn, long id) throws SQLException {
        String sql = "DELETE FROM cloud_sync_queue WHERE id = ?;";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    private CloudSyncRecord mapRow(ResultSet rs) throws SQLException {
        return new CloudSyncRecord(
                rs.getLong("id"),
                rs.getString("backup_file_name"),
                rs.getString("encrypted_file_path"),
                CloudSyncStatus.valueOf(rs.getString("status")),
                rs.getInt("attempts"),
                rs.getString("last_error"),
                rs.getString("created_at"),
                rs.getString("uploaded_at")
        );
    }
}
