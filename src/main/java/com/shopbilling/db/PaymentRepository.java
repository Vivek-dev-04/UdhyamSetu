package com.shopbilling.db;

import com.shopbilling.model.Money;
import com.shopbilling.model.PaymentInput;
import com.shopbilling.model.PaymentMode;
import com.shopbilling.model.PaymentRecord;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Parameterized SQL repository for Sale Payments (PRD Section 7.3, FR-11).
 */
public class PaymentRepository {

    public PaymentRecord insertPayment(Connection conn, long saleId, PaymentInput payment) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        Objects.requireNonNull(payment, "PaymentInput cannot be null");

        String sql = "INSERT INTO sale_payments (sale_id, payment_mode, amount_paise, reference_number) " +
                "VALUES (?, ?, ?, ?);";

        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, saleId);
            ps.setString(2, payment.mode().name());
            ps.setLong(3, payment.amount().getPaise());
            ps.setString(4, payment.referenceNumber());
            ps.executeUpdate();

            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    long id = rs.getLong(1);
                    return new PaymentRecord(
                            id,
                            saleId,
                            payment.mode(),
                            payment.amount(),
                            payment.referenceNumber(),
                            null
                    );
                }
            }
        }
        throw new DatabaseException("Failed to insert payment record");
    }

    public List<PaymentRecord> findBySaleId(Connection conn, long saleId) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        String sql = "SELECT id, sale_id, payment_mode, amount_paise, reference_number, created_at " +
                "FROM sale_payments WHERE sale_id = ? ORDER BY id ASC;";

        List<PaymentRecord> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, saleId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new PaymentRecord(
                            rs.getLong("id"),
                            rs.getLong("sale_id"),
                            PaymentMode.fromString(rs.getString("payment_mode")),
                            Money.ofPaise(rs.getLong("amount_paise")),
                            rs.getString("reference_number"),
                            rs.getString("created_at")
                    ));
                }
            }
        }
        return list;
    }
}
