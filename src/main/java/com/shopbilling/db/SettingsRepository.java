package com.shopbilling.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;

/**
 * Repository for reading and updating shop configuration settings.
 */
public class SettingsRepository {

    public Optional<String> getSetting(Connection conn, String key) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        Objects.requireNonNull(key, "Key cannot be null");

        String sql = "SELECT value FROM settings WHERE key = ?;";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, key.trim().toUpperCase());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.ofNullable(rs.getString("value"));
                }
            }
        }
        return Optional.empty();
    }

    public String getSettingOrDefault(Connection conn, String key, String defaultValue) throws SQLException {
        return getSetting(conn, key).orElse(defaultValue);
    }

    public void setSetting(Connection conn, String key, String value, long updatedByUserId) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        Objects.requireNonNull(key, "Key cannot be null");
        Objects.requireNonNull(value, "Value cannot be null");

        String sql = "INSERT INTO settings (key, value, updated_at, updated_by_user_id) " +
                "VALUES (?, ?, datetime('now', '+05:30'), ?) " +
                "ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at, " +
                "updated_by_user_id = excluded.updated_by_user_id;";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, key.trim().toUpperCase());
            ps.setString(2, value.trim());
            ps.setLong(3, updatedByUserId);
            ps.executeUpdate();
        }
    }
}
