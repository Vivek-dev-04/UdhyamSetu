package com.shopbilling.db;

import com.shopbilling.model.DayEndClosingRecord;
import com.shopbilling.model.Money;

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
 * Parameterized SQL repository for Day-End Closing and daily cash reconciliation
 * (PRD Section 7.9, FR-38 - FR-40).
 */
public class DayEndClosingRepository {

    public DayEndClosingRecord insertClosing(Connection conn, DayEndClosingRecord record) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        Objects.requireNonNull(record, "DayEndClosingRecord cannot be null");

        String sql = "INSERT INTO day_end_closings (closing_date, expected_cash_paise, counted_cash_paise, " +
                "difference_paise, notes, closed_by_user_id) VALUES (?, ?, ?, ?, ?, ?);";

        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, record.closingDate().toString());
            ps.setLong(2, record.expectedCash().getPaise());
            ps.setLong(3, record.countedCash().getPaise());
            ps.setLong(4, record.difference().getPaise());
            ps.setString(5, record.notes() != null ? record.notes().trim() : null);
            ps.setLong(6, record.closedByUserId());

            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    long id = rs.getLong(1);
                    return new DayEndClosingRecord(
                            id,
                            record.closingDate(),
                            record.expectedCash(),
                            record.countedCash(),
                            record.difference(),
                            record.notes(),
                            null,
                            record.closedByUserId()
                    );
                }
            }
        }
        throw new DatabaseException("Failed to insert day-end closing record");
    }

    public Optional<DayEndClosingRecord> findByDate(Connection conn, LocalDate date) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        Objects.requireNonNull(date, "Date cannot be null");

        String sql = "SELECT id, closing_date, expected_cash_paise, counted_cash_paise, difference_paise, " +
                "notes, closed_at, closed_by_user_id FROM day_end_closings WHERE closing_date = ?;";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, date.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRowToClosing(rs));
                }
            }
        }
        return Optional.empty();
    }

    public List<DayEndClosingRecord> getAllClosings(Connection conn) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        String sql = "SELECT id, closing_date, expected_cash_paise, counted_cash_paise, difference_paise, " +
                "notes, closed_at, closed_by_user_id FROM day_end_closings ORDER BY closing_date DESC;";

        List<DayEndClosingRecord> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(mapRowToClosing(rs));
            }
        }
        return list;
    }

    public long getCashSalesForDate(Connection conn, LocalDate date) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        Objects.requireNonNull(date, "Date cannot be null");

        String sql = "SELECT COALESCE(SUM(p.amount_paise), 0) FROM sale_payments p " +
                "JOIN sales s ON p.sale_id = s.id " +
                "WHERE s.sale_date = ? AND p.payment_mode = 'CASH';";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, date.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        }
        return 0L;
    }

    public long getCashRefundsForDate(Connection conn, LocalDate date) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        Objects.requireNonNull(date, "Date cannot be null");

        String sql = "SELECT COALESCE(SUM(total_refund_paise), 0) FROM returns " +
                "WHERE return_date = ? AND refund_mode = 'CASH';";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, date.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        }
        return 0L;
    }

    public long getCashCustomerPaymentsForDate(Connection conn, LocalDate date) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        Objects.requireNonNull(date, "Date cannot be null");

        String sql = "SELECT COALESCE(SUM(ABS(amount_paise)), 0) FROM customer_ledger_entries " +
                "WHERE entry_date = ? AND entry_type = 'PAYMENT_RECEIVED' AND reference_type = 'CASH';";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, date.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        }
        return 0L;
    }

    private DayEndClosingRecord mapRowToClosing(ResultSet rs) throws SQLException {
        return new DayEndClosingRecord(
                rs.getLong("id"),
                LocalDate.parse(rs.getString("closing_date")),
                Money.ofPaise(rs.getLong("expected_cash_paise")),
                Money.ofPaise(rs.getLong("counted_cash_paise")),
                Money.ofPaise(rs.getLong("difference_paise")),
                rs.getString("notes"),
                rs.getString("closed_at"),
                rs.getLong("closed_by_user_id")
        );
    }
}
