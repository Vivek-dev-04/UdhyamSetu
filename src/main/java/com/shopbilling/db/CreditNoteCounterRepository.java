package com.shopbilling.db;

import com.shopbilling.model.CreditNoteNumber;
import com.shopbilling.model.FinancialYear;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;

/**
 * Parameterized SQL repository for atomic per-financial-year credit note counters (PRD Section 7.5, FR-24).
 * Generates gap-free sequential credit note numbers for returns on billed sales.
 */
public class CreditNoteCounterRepository {

    /**
     * Atomically increments and returns the next gap-free credit note number for the financial year.
     * Must be called within an active transaction Connection.
     */
    public CreditNoteNumber nextCreditNoteNumber(Connection conn, FinancialYear financialYear) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        Objects.requireNonNull(financialYear, "FinancialYear cannot be null");

        String fyLabel = financialYear.getLabel();
        String prefix = financialYear.getBillPrefix().replace("FY", "CN");

        // 1. Ensure counter row exists for this financial year
        String insertSql = "INSERT INTO credit_note_counters (financial_year, prefix, last_note_number, updated_at) " +
                "VALUES (?, ?, 0, datetime('now', '+05:30')) " +
                "ON CONFLICT(financial_year) DO NOTHING;";
        try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
            ps.setString(1, fyLabel);
            ps.setString(2, prefix);
            ps.executeUpdate();
        }

        // 2. Increment counter
        String updateSql = "UPDATE credit_note_counters " +
                "SET last_note_number = last_note_number + 1, updated_at = datetime('now', '+05:30') " +
                "WHERE financial_year = ?;";
        try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
            ps.setString(1, fyLabel);
            ps.executeUpdate();
        }

        // 3. Retrieve new sequence number
        String selectSql = "SELECT prefix, last_note_number FROM credit_note_counters WHERE financial_year = ?;";
        try (PreparedStatement ps = conn.prepareStatement(selectSql)) {
            ps.setString(1, fyLabel);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String fetchedPrefix = rs.getString("prefix");
                    long seq = rs.getLong("last_note_number");
                    return CreditNoteNumber.of(fyLabel, fetchedPrefix, seq);
                }
            }
        }

        throw new DatabaseException("Failed to generate credit note number for financial year " + fyLabel);
    }

    public Optional<CreditNoteNumber> getCurrentCreditNoteNumber(Connection conn, FinancialYear financialYear) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        Objects.requireNonNull(financialYear, "FinancialYear cannot be null");

        String sql = "SELECT prefix, last_note_number FROM credit_note_counters WHERE financial_year = ?;";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, financialYear.getLabel());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long seq = rs.getLong("last_note_number");
                    if (seq > 0) {
                        return Optional.of(CreditNoteNumber.of(financialYear.getLabel(), rs.getString("prefix"), seq));
                    }
                }
            }
        }
        return Optional.empty();
    }
}
