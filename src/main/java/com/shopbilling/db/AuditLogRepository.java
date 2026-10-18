package com.shopbilling.db;

import com.shopbilling.model.AuditLogRecord;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Parameterized SQL repository for append-only audit logging (PRD Section 7.10, FR-44).
 * Records can never be updated or deleted.
 */
public class AuditLogRepository {

    public AuditLogRecord insert(Connection conn,
                                 long userId,
                                 String action,
                                 String entityName,
                                 String entityId,
                                 String oldValue,
                                 String newValue,
                                 String reason) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        Objects.requireNonNull(action, "Action cannot be null");
        Objects.requireNonNull(entityName, "EntityName cannot be null");

        String sql = "INSERT INTO audit_log (user_id, action, entity_name, entity_id, old_value, new_value, reason) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?);";

        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, userId);
            ps.setString(2, action.trim());
            ps.setString(3, entityName.trim());
            ps.setString(4, entityId != null ? entityId.trim() : null);
            ps.setString(5, oldValue);
            ps.setString(6, newValue);
            ps.setString(7, reason != null ? reason.trim() : null);

            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    long id = rs.getLong(1);
                    return new AuditLogRecord(
                            id,
                            userId,
                            null, // Username populated on queries
                            action.trim(),
                            entityName.trim(),
                            entityId != null ? entityId.trim() : null,
                            oldValue,
                            newValue,
                            reason != null ? reason.trim() : null,
                            null
                    );
                }
            }
        }
        throw new DatabaseException("Failed to insert audit log entry");
    }

    public List<AuditLogRecord> queryLogs(Connection conn,
                                          LocalDate from,
                                          LocalDate to,
                                          String action,
                                          Long userId,
                                          int limit,
                                          int offset) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT a.id, a.user_id, u.username, a.action, a.entity_name, a.entity_id, ")
                .append("a.old_value, a.new_value, a.reason, a.created_at ")
                .append("FROM audit_log a ")
                .append("LEFT JOIN users u ON a.user_id = u.id ")
                .append("WHERE 1=1 ");

        List<Object> params = new ArrayList<>();
        if (from != null) {
            sql.append("AND a.created_at >= ? ");
            params.add(from.toString() + " 00:00:00");
        }
        if (to != null) {
            sql.append("AND a.created_at <= ? ");
            params.add(to.toString() + " 23:59:59");
        }
        if (action != null && !action.trim().isEmpty()) {
            sql.append("AND a.action = ? ");
            params.add(action.trim());
        }
        if (userId != null) {
            sql.append("AND a.user_id = ? ");
            params.add(userId);
        }

        sql.append("ORDER BY a.created_at DESC, a.id DESC LIMIT ? OFFSET ?;");
        params.add(Math.max(1, limit));
        params.add(Math.max(0, offset));

        List<AuditLogRecord> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapRow(rs));
                }
            }
        }
        return list;
    }

    public List<AuditLogRecord> getRecent(Connection conn, int limit) throws SQLException {
        return queryLogs(conn, null, null, null, null, limit, 0);
    }

    private AuditLogRecord mapRow(ResultSet rs) throws SQLException {
        return new AuditLogRecord(
                rs.getLong("id"),
                rs.getLong("user_id"),
                rs.getString("username"),
                rs.getString("action"),
                rs.getString("entity_name"),
                rs.getString("entity_id"),
                rs.getString("old_value"),
                rs.getString("new_value"),
                rs.getString("reason"),
                rs.getString("created_at")
        );
    }
}
