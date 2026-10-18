package com.shopbilling.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;

/**
 * Parameterized SQL repository for Customer Ledger entries (PRD Section 7.6, FR-26, FR-30).
 * Append-only: customer ledger entries are never edited or deleted.
 */
public class CustomerLedgerRepository {

    public long insertEntry(Connection conn,
                            long customerId,
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

        String sql = "INSERT INTO customer_ledger_entries (customer_id, entry_type, amount_paise, " +
                "reference_type, reference_id, entry_date, notes, created_by_user_id) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?);";

        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, customerId);
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
        throw new DatabaseException("Failed to insert customer ledger entry");
    }

    public long getCurrentDuesPaise(Connection conn, long customerId) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        String sql = "SELECT COALESCE(SUM(amount_paise), 0) FROM customer_ledger_entries WHERE customer_id = ?;";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, customerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        }
        return 0L;
    }

    public java.util.List<com.shopbilling.model.CustomerLedgerEntry> getEntriesForCustomer(Connection conn, long customerId) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        String sql = "SELECT id, customer_id, entry_type, amount_paise, reference_type, reference_id, " +
                "entry_date, notes, created_at, created_by_user_id FROM customer_ledger_entries " +
                "WHERE customer_id = ? ORDER BY entry_date ASC, id ASC;";

        java.util.List<com.shopbilling.model.CustomerLedgerEntry> list = new java.util.ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, customerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new com.shopbilling.model.CustomerLedgerEntry(
                            rs.getLong("id"),
                            rs.getLong("customer_id"),
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

    public com.shopbilling.model.PaymentAllocation insertAllocation(Connection conn,
                                                                    long paymentLedgerEntryId,
                                                                    long saleId,
                                                                    long allocatedAmountPaise) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        String sql = "INSERT INTO customer_payment_allocations (payment_ledger_entry_id, sale_id, allocated_amount_paise) " +
                "VALUES (?, ?, ?);";

        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, paymentLedgerEntryId);
            ps.setLong(2, saleId);
            ps.setLong(3, allocatedAmountPaise);
            ps.executeUpdate();

            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    long id = rs.getLong(1);
                    return new com.shopbilling.model.PaymentAllocation(
                            id,
                            paymentLedgerEntryId,
                            saleId,
                            com.shopbilling.model.Money.ofPaise(allocatedAmountPaise),
                            null
                    );
                }
            }
        }
        throw new DatabaseException("Failed to insert customer payment allocation");
    }

    public java.util.List<com.shopbilling.model.PaymentAllocation> getAllocationsForPayment(Connection conn, long paymentLedgerEntryId) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        String sql = "SELECT id, payment_ledger_entry_id, sale_id, allocated_amount_paise, created_at " +
                "FROM customer_payment_allocations WHERE payment_ledger_entry_id = ? ORDER BY id ASC;";

        java.util.List<com.shopbilling.model.PaymentAllocation> list = new java.util.ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, paymentLedgerEntryId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new com.shopbilling.model.PaymentAllocation(
                            rs.getLong("id"),
                            rs.getLong("payment_ledger_entry_id"),
                            rs.getLong("sale_id"),
                            com.shopbilling.model.Money.ofPaise(rs.getLong("allocated_amount_paise")),
                            rs.getString("created_at")
                    ));
                }
            }
        }
        return list;
    }

    public long getTotalAllocatedForSale(Connection conn, long saleId) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        String sql = "SELECT COALESCE(SUM(allocated_amount_paise), 0) FROM customer_payment_allocations WHERE sale_id = ?;";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, saleId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        }
        return 0L;
    }

    public java.util.List<com.shopbilling.model.CustomerDuesSummary> getAllCustomersWithDues(Connection conn) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        String sql = "SELECT c.id, c.name, c.phone, c.customer_type, c.credit_limit_paise, " +
                "COALESCE(SUM(e.amount_paise), 0) as current_dues_paise " +
                "FROM customers c " +
                "LEFT JOIN customer_ledger_entries e ON c.id = e.customer_id " +
                "GROUP BY c.id " +
                "ORDER BY current_dues_paise DESC, c.name ASC;";

        java.util.List<com.shopbilling.model.CustomerDuesSummary> list = new java.util.ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                long limitVal = rs.getLong("credit_limit_paise");
                com.shopbilling.model.Money limit = rs.wasNull() ? null : com.shopbilling.model.Money.ofPaise(limitVal);
                list.add(new com.shopbilling.model.CustomerDuesSummary(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getString("phone"),
                        com.shopbilling.model.CustomerType.valueOf(rs.getString("customer_type")),
                        com.shopbilling.model.Money.ofPaise(rs.getLong("current_dues_paise")),
                        limit
                ));
            }
        }
        return list;
    }
}
