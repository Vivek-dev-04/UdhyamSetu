package com.shopbilling.db;

import com.shopbilling.model.HsnVerificationAuditRecord;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Parameterized SQLite repository for HSN Classification Audit Trail (Section 10).
 */
public class HsnAuditRepository {

    public long logAudit(Connection conn, HsnVerificationAuditRecord record) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        Objects.requireNonNull(record, "HsnVerificationAuditRecord cannot be null");

        String sql = "INSERT INTO hsn_verification_history (product_id, action, previous_hsn, new_hsn, previous_gst_rate, new_gst_rate, user_id, reason, verification_source, status, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, datetime('now', '+05:30'));";

        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, record.productId());
            ps.setString(2, record.action());
            ps.setString(3, record.previousHsn());
            ps.setString(4, record.newHsn());
            if (record.previousGstRate() != null) {
                ps.setDouble(5, record.previousGstRate());
            } else {
                ps.setNull(5, Types.REAL);
            }
            if (record.newGstRate() != null) {
                ps.setDouble(6, record.newGstRate());
            } else {
                ps.setNull(6, Types.REAL);
            }
            if (record.userId() != null) {
                ps.setLong(7, record.userId());
            } else {
                ps.setNull(7, Types.INTEGER);
            }
            ps.setString(8, record.reason());
            ps.setString(9, record.verificationSource());
            ps.setString(10, record.status());
            ps.executeUpdate();

            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        }
        return -1;
    }

    public List<HsnVerificationAuditRecord> findByProductId(Connection conn, long productId) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        String sql = "SELECT id, product_id, action, previous_hsn, new_hsn, previous_gst_rate, new_gst_rate, user_id, reason, verification_source, status, created_at " +
                "FROM hsn_verification_history WHERE product_id = ? ORDER BY id DESC;";

        List<HsnVerificationAuditRecord> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, productId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapRow(rs));
                }
            }
        }
        return list;
    }

    public List<HsnVerificationAuditRecord> findRecentAudits(Connection conn, int limit) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        int safeLimit = limit <= 0 ? 50 : limit;
        String sql = "SELECT id, product_id, action, previous_hsn, new_hsn, previous_gst_rate, new_gst_rate, user_id, reason, verification_source, status, created_at " +
                "FROM hsn_verification_history ORDER BY id DESC LIMIT ?;";

        List<HsnVerificationAuditRecord> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, safeLimit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapRow(rs));
                }
            }
        }
        return list;
    }

    private HsnVerificationAuditRecord mapRow(ResultSet rs) throws SQLException {
        double prevRate = rs.getDouble("previous_gst_rate");
        Double prevRateVal = rs.wasNull() ? null : prevRate;
        double newRate = rs.getDouble("new_gst_rate");
        Double newRateVal = rs.wasNull() ? null : newRate;
        long uId = rs.getLong("user_id");
        Long userVal = rs.wasNull() ? null : uId;

        return new HsnVerificationAuditRecord(
                rs.getLong("id"),
                rs.getLong("product_id"),
                rs.getString("action"),
                rs.getString("previous_hsn"),
                rs.getString("new_hsn"),
                prevRateVal,
                newRateVal,
                userVal,
                rs.getString("reason"),
                rs.getString("verification_source"),
                rs.getString("status"),
                rs.getString("created_at")
        );
    }
}
