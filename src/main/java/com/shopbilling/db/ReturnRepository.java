package com.shopbilling.db;

import com.shopbilling.model.Money;
import com.shopbilling.model.PaymentMode;
import com.shopbilling.model.Quantity;
import com.shopbilling.model.ReturnCondition;
import com.shopbilling.model.ReturnItemRecord;
import com.shopbilling.model.ReturnRecord;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Parameterized SQL repository for customer returns and return items (PRD Section 7.5, 11).
 * Append-only: returns and return items are never modified or deleted.
 */
public class ReturnRepository {

    public ReturnRecord insertReturn(Connection conn, ReturnRecord record) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        Objects.requireNonNull(record, "ReturnRecord cannot be null");

        String sql = "INSERT INTO returns (sale_id, credit_note_number, financial_year, return_date, " +
                "refund_mode, total_refund_paise, notes, created_by_user_id) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?);";

        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, record.saleId());
            if (record.creditNoteNumber() != null) {
                ps.setString(2, record.creditNoteNumber().trim());
            } else {
                ps.setNull(2, Types.VARCHAR);
            }
            ps.setString(3, record.financialYear().trim());
            ps.setString(4, record.returnDate().toString());
            ps.setString(5, record.refundMode().name());
            ps.setLong(6, record.totalRefund().getPaise());
            if (record.notes() != null) {
                ps.setString(7, record.notes().trim());
            } else {
                ps.setNull(7, Types.VARCHAR);
            }
            ps.setLong(8, record.createdByUserId());

            ps.executeUpdate();

            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    long generatedId = rs.getLong(1);
                    return new ReturnRecord(
                            generatedId,
                            record.saleId(),
                            record.creditNoteNumber(),
                            record.financialYear(),
                            record.returnDate(),
                            record.refundMode(),
                            record.totalRefund(),
                            record.notes(),
                            null,
                            record.createdByUserId(),
                            record.items()
                    );
                }
            }
        }
        throw new DatabaseException("Failed to insert return record");
    }

    public ReturnItemRecord insertReturnItem(Connection conn, ReturnItemRecord item) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        Objects.requireNonNull(item, "ReturnItemRecord cannot be null");

        String sql = "INSERT INTO return_items (return_id, sale_item_id, variant_id, quantity_scaled, " +
                "condition, refund_paise, reason) VALUES (?, ?, ?, ?, ?, ?, ?);";

        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, item.returnId());
            ps.setLong(2, item.saleItemId());
            ps.setLong(3, item.variantId());
            ps.setLong(4, item.quantity().getScaledValue());
            ps.setString(5, item.condition().name());
            ps.setLong(6, item.refundAmount().getPaise());
            ps.setString(7, item.reason().trim());

            ps.executeUpdate();

            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    long generatedId = rs.getLong(1);
                    return new ReturnItemRecord(
                            generatedId,
                            item.returnId(),
                            item.saleItemId(),
                            item.variantId(),
                            item.quantity(),
                            item.condition(),
                            item.refundAmount(),
                            item.reason()
                    );
                }
            }
        }
        throw new DatabaseException("Failed to insert return item");
    }

    public Optional<ReturnRecord> findById(Connection conn, long returnId) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        String sql = "SELECT id, sale_id, credit_note_number, financial_year, return_date, " +
                "refund_mode, total_refund_paise, notes, created_at, created_by_user_id " +
                "FROM returns WHERE id = ?;";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, returnId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRowToReturn(rs));
                }
            }
        }
        return Optional.empty();
    }

    public List<ReturnRecord> findBySaleId(Connection conn, long saleId) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        String sql = "SELECT id, sale_id, credit_note_number, financial_year, return_date, " +
                "refund_mode, total_refund_paise, notes, created_at, created_by_user_id " +
                "FROM returns WHERE sale_id = ? ORDER BY id ASC;";

        List<ReturnRecord> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, saleId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapRowToReturn(rs));
                }
            }
        }
        return list;
    }

    public List<ReturnItemRecord> findItemsByReturnId(Connection conn, long returnId) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        String sql = "SELECT id, return_id, sale_item_id, variant_id, quantity_scaled, " +
                "condition, refund_paise, reason FROM return_items WHERE return_id = ? ORDER BY id ASC;";

        List<ReturnItemRecord> items = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, returnId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    items.add(new ReturnItemRecord(
                            rs.getLong("id"),
                            rs.getLong("return_id"),
                            rs.getLong("sale_item_id"),
                            rs.getLong("variant_id"),
                            Quantity.ofScaled(rs.getLong("quantity_scaled")),
                            ReturnCondition.fromString(rs.getString("condition")),
                            Money.ofPaise(rs.getLong("refund_paise")),
                            rs.getString("reason")
                    ));
                }
            }
        }
        return items;
    }

    public Quantity getTotalReturnedQuantityForSaleItem(Connection conn, long saleItemId) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        String sql = "SELECT COALESCE(SUM(quantity_scaled), 0) FROM return_items WHERE sale_item_id = ?;";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, saleItemId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Quantity.ofScaled(rs.getLong(1));
                }
            }
        }
        return Quantity.ZERO;
    }

    private ReturnRecord mapRowToReturn(ResultSet rs) throws SQLException {
        return new ReturnRecord(
                rs.getLong("id"),
                rs.getLong("sale_id"),
                rs.getString("credit_note_number"),
                rs.getString("financial_year"),
                LocalDate.parse(rs.getString("return_date")),
                PaymentMode.valueOf(rs.getString("refund_mode")),
                Money.ofPaise(rs.getLong("total_refund_paise")),
                rs.getString("notes"),
                rs.getString("created_at"),
                rs.getLong("created_by_user_id"),
                List.of()
        );
    }
}
