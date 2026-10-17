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
}
