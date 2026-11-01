package com.shopbilling.db;

import com.shopbilling.model.GstRateItem;
import com.shopbilling.model.HsnItem;

import java.sql.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Parameterized SQLite repository for Statutory GST Rates & Notifications (Section 8).
 * Supports effective-dated rate lookups and preserves historical tax schedules.
 */
public class GstRateRepository {

    public GstRateItem insert(Connection conn, GstRateItem item) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        Objects.requireNonNull(item, "GstRateItem cannot be null");

        String sql = "INSERT INTO gst_rate_master (hsn_code, gst_rate, cgst_rate, sgst_rate, igst_rate, effective_from, effective_until, notification_reference, verification_status, source, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, datetime('now', '+05:30'));";

        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, HsnItem.cleanCode(item.hsnCode()));
            ps.setDouble(2, item.gstRate());
            ps.setDouble(3, item.cgstRate());
            ps.setDouble(4, item.sgstRate());
            ps.setDouble(5, item.igstRate());
            ps.setString(6, item.effectiveFrom());
            ps.setString(7, item.effectiveUntil() != null ? item.effectiveUntil() : "9999-12-31");
            ps.setString(8, item.notificationReference());
            ps.setString(9, item.verificationStatus());
            ps.setString(10, item.source());
            ps.executeUpdate();

            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    long id = rs.getLong(1);
                    return findById(conn, id)
                            .orElseThrow(() -> new DatabaseException("Failed to retrieve created GST rate item id=" + id));
                }
            }
        }
        throw new DatabaseException("Failed to insert GST rate item");
    }

    public Optional<GstRateItem> findById(Connection conn, long id) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        String sql = "SELECT id, hsn_code, gst_rate, cgst_rate, sgst_rate, igst_rate, effective_from, effective_until, notification_reference, verification_status, source, created_at " +
                "FROM gst_rate_master WHERE id = ?;";
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

    /**
     * Finds the applicable statutory GST rate for an HSN code on an invoice transaction date.
     * Evaluates exact HSN matches first, then falls back to 6-digit, 4-digit heading, or 2-digit chapter rate.
     */
    public Optional<GstRateItem> findApplicableRate(Connection conn, String hsnCode, LocalDate date) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        if (hsnCode == null || hsnCode.isBlank()) return Optional.empty();
        String dateStr = (date != null ? date : LocalDate.now()).toString();
        String clean = HsnItem.cleanCode(hsnCode);

        // Hierarchical match: Exact code -> 6-digit -> 4-digit -> 2-digit
        String sql = "SELECT id, hsn_code, gst_rate, cgst_rate, sgst_rate, igst_rate, effective_from, effective_until, notification_reference, verification_status, source, created_at " +
                "FROM gst_rate_master " +
                "WHERE ? LIKE (hsn_code || '%') " +
                "  AND effective_from <= ? AND effective_until >= ? " +
                "ORDER BY LENGTH(hsn_code) DESC LIMIT 1;";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, clean);
            ps.setString(2, dateStr);
            ps.setString(3, dateStr);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
        }
        return Optional.empty();
    }

    public List<GstRateItem> findAllCurrent(Connection conn) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        String today = LocalDate.now().toString();
        String sql = "SELECT id, hsn_code, gst_rate, cgst_rate, sgst_rate, igst_rate, effective_from, effective_until, notification_reference, verification_status, source, created_at " +
                "FROM gst_rate_master WHERE effective_from <= ? AND effective_until >= ? ORDER BY hsn_code ASC;";

        List<GstRateItem> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, today);
            ps.setString(2, today);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapRow(rs));
                }
            }
        }
        return list;
    }

    private GstRateItem mapRow(ResultSet rs) throws SQLException {
        return new GstRateItem(
                rs.getLong("id"),
                rs.getString("hsn_code"),
                rs.getDouble("gst_rate"),
                rs.getDouble("cgst_rate"),
                rs.getDouble("sgst_rate"),
                rs.getDouble("igst_rate"),
                rs.getString("effective_from"),
                rs.getString("effective_until"),
                rs.getString("notification_reference"),
                rs.getString("verification_status"),
                rs.getString("source"),
                rs.getString("created_at")
        );
    }
}
