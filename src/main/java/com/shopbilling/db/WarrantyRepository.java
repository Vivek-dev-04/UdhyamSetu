package com.shopbilling.db;

import com.shopbilling.model.WarrantyRecord;
import com.shopbilling.model.WarrantyStatus;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Parameterized SQL repository for item serial numbers and warranties (PRD Section 11, Section 15.6).
 */
public class WarrantyRepository {

    public WarrantyRecord insertWarranty(Connection conn,
                                         long saleId,
                                         long variantId,
                                         String serialNumber,
                                         int warrantyMonths,
                                         LocalDate startDate,
                                         LocalDate endDate,
                                         long userId) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        Objects.requireNonNull(serialNumber, "Serial number cannot be null");
        Objects.requireNonNull(startDate, "Start date cannot be null");
        Objects.requireNonNull(endDate, "End date cannot be null");

        String sql = "INSERT INTO item_warranties " +
                "(sale_id, variant_id, serial_number, warranty_months, warranty_start_date, warranty_end_date, created_by_user_id) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?);";

        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, saleId);
            ps.setLong(2, variantId);
            ps.setString(3, serialNumber.trim().toUpperCase());
            ps.setInt(4, warrantyMonths);
            ps.setString(5, startDate.toString());
            ps.setString(6, endDate.toString());
            ps.setLong(7, userId);

            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    long id = rs.getLong(1);
                    return findById(conn, id)
                            .orElseThrow(() -> new DatabaseException("Failed to find created warranty id=" + id));
                }
            }
        }
        throw new DatabaseException("Failed to insert item warranty");
    }

    public Optional<WarrantyRecord> findById(Connection conn, long id) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        String sql = selectWarrantySql() + " WHERE w.id = ?;";
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

    public Optional<WarrantyRecord> findBySerialNumber(Connection conn, String serialNumber) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        Objects.requireNonNull(serialNumber, "Serial number cannot be null");

        String sql = selectWarrantySql() + " WHERE UPPER(w.serial_number) = ?;";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, serialNumber.trim().toUpperCase());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
        }
        return Optional.empty();
    }

    public List<WarrantyRecord> findBySaleId(Connection conn, long saleId) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        String sql = selectWarrantySql() + " WHERE w.sale_id = ? ORDER BY w.id ASC;";
        List<WarrantyRecord> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, saleId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapRow(rs));
                }
            }
        }
        return list;
    }

    public void updateStatus(Connection conn, long id, WarrantyStatus status, String claimNotes) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        Objects.requireNonNull(status, "WarrantyStatus cannot be null");

        String sql = "UPDATE item_warranties SET status = ?, claim_notes = ? WHERE id = ?;";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status.name());
            ps.setString(2, claimNotes != null ? claimNotes.trim() : null);
            ps.setLong(3, id);
            ps.executeUpdate();
        }
    }

    public List<WarrantyRecord> findExpiringWithin(Connection conn, LocalDate asOfDate, int daysAhead) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        Objects.requireNonNull(asOfDate, "AsOfDate cannot be null");
        String maxDate = asOfDate.plusDays(daysAhead).toString();
        String sql = selectWarrantySql() +
                " WHERE w.status = 'ACTIVE' AND w.warranty_end_date BETWEEN ? AND ? ORDER BY w.warranty_end_date ASC;";
        List<WarrantyRecord> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, asOfDate.toString());
            ps.setString(2, maxDate);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapRow(rs));
                }
            }
        }
        return list;
    }

    public boolean isSerialNumberRegistered(Connection conn, String serialNumber) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        Objects.requireNonNull(serialNumber, "Serial number cannot be null");

        String sql = "SELECT 1 FROM item_warranties WHERE UPPER(serial_number) = ?;";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, serialNumber.trim().toUpperCase());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private String selectWarrantySql() {
        return "SELECT w.id, w.sale_id, s.bill_number, w.variant_id, " +
                "p.name as product_name, pv.variant_name, w.serial_number, " +
                "w.warranty_months, w.warranty_start_date, w.warranty_end_date, " +
                "w.status, c.name as customer_name, c.phone as customer_phone, " +
                "w.claim_notes, w.created_at, w.created_by_user_id " +
                "FROM item_warranties w " +
                "JOIN sales s ON w.sale_id = s.id " +
                "JOIN product_variants pv ON w.variant_id = pv.id " +
                "JOIN products p ON pv.product_id = p.id " +
                "LEFT JOIN customers c ON s.customer_id = c.id";
    }

    private WarrantyRecord mapRow(ResultSet rs) throws SQLException {
        return new WarrantyRecord(
                rs.getLong("id"),
                rs.getLong("sale_id"),
                rs.getString("bill_number"),
                rs.getLong("variant_id"),
                rs.getString("product_name"),
                rs.getString("variant_name"),
                rs.getString("serial_number"),
                rs.getInt("warranty_months"),
                LocalDate.parse(rs.getString("warranty_start_date")),
                LocalDate.parse(rs.getString("warranty_end_date")),
                WarrantyStatus.valueOf(rs.getString("status")),
                rs.getString("customer_name"),
                rs.getString("customer_phone"),
                rs.getString("claim_notes"),
                rs.getString("created_at"),
                rs.getLong("created_by_user_id")
        );
    }
}
