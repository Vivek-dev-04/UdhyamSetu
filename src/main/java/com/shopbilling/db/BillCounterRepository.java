package com.shopbilling.db;

import com.shopbilling.model.BillNumber;
import com.shopbilling.model.FinancialYear;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;

/**
 * Parameterized SQL repository for atomic per-financial-year bill counters (PRD Section 6 & Hard Rule 6).
 */
public class BillCounterRepository {

    /**
     * Atomically increments and returns the next gap-free bill number for the financial year.
     * Must be called within an active transaction Connection.
     */
    public BillNumber nextBillNumber(Connection conn, FinancialYear financialYear) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        Objects.requireNonNull(financialYear, "FinancialYear cannot be null");

        String fyLabel = financialYear.getLabel();
        String prefix = financialYear.getBillPrefix();

        // 1. Ensure counter row exists for this financial year
        String insertSql = "INSERT INTO bill_counters (financial_year, prefix, last_bill_number, updated_at) " +
                "VALUES (?, ?, 0, datetime('now', '+05:30')) " +
                "ON CONFLICT(financial_year) DO NOTHING;";
        try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
            ps.setString(1, fyLabel);
            ps.setString(2, prefix);
            ps.executeUpdate();
        }

        // 2. Increment counter
        String updateSql = "UPDATE bill_counters " +
                "SET last_bill_number = last_bill_number + 1, updated_at = datetime('now', '+05:30') " +
                "WHERE financial_year = ?;";
        try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
            ps.setString(1, fyLabel);
            ps.executeUpdate();
        }

        // 3. Retrieve new sequence number
        String selectSql = "SELECT prefix, last_bill_number FROM bill_counters WHERE financial_year = ?;";
        try (PreparedStatement ps = conn.prepareStatement(selectSql)) {
            ps.setString(1, fyLabel);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String fetchedPrefix = rs.getString("prefix");
                    long seq = rs.getLong("last_bill_number");
                    return BillNumber.of(fyLabel, fetchedPrefix, seq);
                }
            }
        }

        throw new DatabaseException("Failed to generate bill number for financial year " + fyLabel);
    }

    public Optional<BillNumber> getCurrentBillNumber(Connection conn, FinancialYear financialYear) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        Objects.requireNonNull(financialYear, "FinancialYear cannot be null");

        String sql = "SELECT prefix, last_bill_number FROM bill_counters WHERE financial_year = ?;";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, financialYear.getLabel());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long seq = rs.getLong("last_bill_number");
                    if (seq > 0) {
                        return Optional.of(BillNumber.of(financialYear.getLabel(), rs.getString("prefix"), seq));
                    }
                }
            }
        }
        return Optional.empty();
    }
}
