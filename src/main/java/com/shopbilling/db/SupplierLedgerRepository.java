package com.shopbilling.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;

/**
 * Parameterized SQL repository for Supplier Ledger entries (PRD Section 7.6, FR-29).
 * Append-only: ledger entries are never edited or deleted.
 */
public class SupplierLedgerRepository {

    public long insertEntry(Connection conn,
                            long supplierId,
                            String entryType,
                            long amountPaise,
                            String referenceType,
                            long referenceId,
                            String entryDate,
                            String notes,
                            long userId) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        Objects.requireNonNull(entryType, "Entry type cannot be null");
        Objects.requireNonNull(referenceType, "Reference type cannot be null");
        Objects.requireNonNull(entryDate, "Entry date cannot be null");

        String sql = "INSERT INTO supplier_ledger_entries (supplier_id, entry_type, amount_paise, " +
                "reference_type, reference_id, entry_date, notes, created_by_user_id) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?);";

        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, supplierId);
            ps.setString(2, entryType.trim().toUpperCase());
            ps.setLong(3, amountPaise);
            ps.setString(4, referenceType.trim().toUpperCase());
            ps.setLong(5, referenceId);
            ps.setString(6, entryDate.trim());
            ps.setString(7, notes != null ? notes.trim() : null);
            ps.setLong(8, userId);
            ps.executeUpdate();

            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        }
        throw new DatabaseException("Failed to insert supplier ledger entry");
    }

    public long getCurrentPayablePaise(Connection conn, long supplierId) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        String sql = "SELECT COALESCE(SUM(amount_paise), 0) FROM supplier_ledger_entries WHERE supplier_id = ?;";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, supplierId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        }
        return 0L;
    }

    public java.util.List<com.shopbilling.model.SupplierLedgerEntry> getEntriesForSupplier(Connection conn, long supplierId) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        String sql = "SELECT id, supplier_id, entry_type, amount_paise, reference_type, reference_id, " +
                "entry_date, notes, created_at, created_by_user_id FROM supplier_ledger_entries " +
                "WHERE supplier_id = ? ORDER BY entry_date ASC, id ASC;";

        java.util.List<com.shopbilling.model.SupplierLedgerEntry> list = new java.util.ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, supplierId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new com.shopbilling.model.SupplierLedgerEntry(
                            rs.getLong("id"),
                            rs.getLong("supplier_id"),
                            rs.getString("entry_type"),
                            com.shopbilling.model.Money.ofPaise(rs.getLong("amount_paise")),
                            rs.getString("reference_type"),
                            rs.getLong("reference_id"),
                            java.time.LocalDate.parse(rs.getString("entry_date")),
                            rs.getString("notes"),
                            rs.getString("created_at"),
                            rs.getLong("created_by_user_id")
                    ));
                }
            }
        }
        return list;
    }

    public java.util.List<com.shopbilling.model.SupplierPayableSummary> getAllSuppliersWithPayables(Connection conn) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        String sql = "SELECT s.id, s.name, s.phone, s.contact_person, " +
                "COALESCE(SUM(e.amount_paise), 0) as current_payable_paise " +
                "FROM suppliers s " +
                "LEFT JOIN supplier_ledger_entries e ON s.id = e.supplier_id " +
                "GROUP BY s.id " +
                "ORDER BY current_payable_paise DESC, s.name ASC;";

        java.util.List<com.shopbilling.model.SupplierPayableSummary> list = new java.util.ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(new com.shopbilling.model.SupplierPayableSummary(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getString("phone"),
                        rs.getString("contact_person"),
                        com.shopbilling.model.Money.ofPaise(rs.getLong("current_payable_paise"))
                ));
            }
        }
        return list;
    }
}
