package com.shopbilling.db;

import com.shopbilling.model.Money;
import com.shopbilling.model.Quantity;
import com.shopbilling.model.Sale;
import com.shopbilling.model.SaleItem;
import com.shopbilling.model.SaleMode;

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
 * Parameterized SQL repository for sales and sale line items (PRD Section 6, 7.3, 11).
 * Append-only: sales and sale items are never updated or deleted.
 */
public class SaleRepository {

    public Sale insertSale(Connection conn, Sale sale) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        Objects.requireNonNull(sale, "Sale cannot be null");

        String sql = "INSERT INTO sales (sale_mode, financial_year, bill_number, bill_sequence, customer_id, " +
                "sale_date, subtotal_paise, discount_paise, total_amount_paise, created_by_user_id) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?);";

        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, sale.saleMode().name());
            ps.setString(2, sale.financialYear());
            if (sale.billNumber() != null) {
                ps.setString(3, sale.billNumber().trim());
            } else {
                ps.setNull(3, Types.VARCHAR);
            }
            if (sale.billSequence() != null) {
                ps.setInt(4, sale.billSequence());
            } else {
                ps.setNull(4, Types.INTEGER);
            }
            if (sale.customerId() != null) {
                ps.setLong(5, sale.customerId());
            } else {
                ps.setNull(5, Types.INTEGER);
            }
            ps.setString(6, sale.saleDate().toString());
            ps.setLong(7, sale.subtotal().getPaise());
            ps.setLong(8, sale.discount().getPaise());
            ps.setLong(9, sale.totalAmount().getPaise());
            ps.setLong(10, sale.createdByUserId());

            ps.executeUpdate();

            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    long generatedId = rs.getLong(1);
                    return new Sale(
                            generatedId,
                            sale.saleMode(),
                            sale.financialYear(),
                            sale.billNumber(),
                            sale.billSequence(),
                            sale.customerId(),
                            sale.saleDate(),
                            sale.createdAt(),
                            sale.subtotal(),
                            sale.discount(),
                            sale.totalAmount(),
                            sale.createdByUserId(),
                            sale.items(),
                            sale.payments()
                    );
                }
            }
        }
        throw new DatabaseException("Failed to insert sale record");
    }

    public SaleItem insertSaleItem(Connection conn, SaleItem item) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        Objects.requireNonNull(item, "SaleItem cannot be null");

        String sql = "INSERT INTO sale_items (sale_id, variant_id, quantity_scaled, unit_price_paise, " +
                "line_discount_paise, line_total_paise) VALUES (?, ?, ?, ?, ?, ?);";

        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, item.saleId());
            ps.setLong(2, item.variantId());
            ps.setLong(3, item.quantity().getScaledValue());
            ps.setLong(4, item.unitPrice().getPaise());
            ps.setLong(5, item.lineDiscount().getPaise());
            ps.setLong(6, item.lineTotal().getPaise());

            ps.executeUpdate();

            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    long generatedId = rs.getLong(1);
                    return new SaleItem(
                            generatedId,
                            item.saleId(),
                            item.variantId(),
                            item.quantity(),
                            item.unitPrice(),
                            item.lineDiscount(),
                            item.lineTotal()
                    );
                }
            }
        }
        throw new DatabaseException("Failed to insert sale item");
    }

    public Optional<Sale> findById(Connection conn, long saleId) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        String sql = "SELECT id, sale_mode, financial_year, bill_number, bill_sequence, customer_id, " +
                "sale_date, created_at, subtotal_paise, discount_paise, total_amount_paise, created_by_user_id " +
                "FROM sales WHERE id = ?;";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, saleId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRowToSale(rs));
                }
            }
        }
        return Optional.empty();
    }

    public Optional<Sale> findByBillNumber(Connection conn, String billNumber) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        if (billNumber == null || billNumber.trim().isEmpty()) {
            return Optional.empty();
        }
        String sql = "SELECT id, sale_mode, financial_year, bill_number, bill_sequence, customer_id, " +
                "sale_date, created_at, subtotal_paise, discount_paise, total_amount_paise, created_by_user_id " +
                "FROM sales WHERE bill_number = ?;";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, billNumber.trim());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRowToSale(rs));
                }
            }
        }
        return Optional.empty();
    }

    public List<SaleItem> findItemsBySaleId(Connection conn, long saleId) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        String sql = "SELECT id, sale_id, variant_id, quantity_scaled, unit_price_paise, " +
                "line_discount_paise, line_total_paise FROM sale_items WHERE sale_id = ? ORDER BY id ASC;";

        List<SaleItem> items = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, saleId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    items.add(new SaleItem(
                            rs.getLong("id"),
                            rs.getLong("sale_id"),
                            rs.getLong("variant_id"),
                            Quantity.ofScaled(rs.getLong("quantity_scaled")),
                            Money.ofPaise(rs.getLong("unit_price_paise")),
                            Money.ofPaise(rs.getLong("line_discount_paise")),
                            Money.ofPaise(rs.getLong("line_total_paise"))
                    ));
                }
            }
        }
        return items;
    }

    /**
     * Assigns a bill number to an existing registered sale (PRD Section 6, FR-1).
     * Converts sale_mode from REGISTERED to BILL without altering turnover or stock.
     */
    public void assignBillNumber(Connection conn, long saleId, String billNumber, int billSequence) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        Objects.requireNonNull(billNumber, "Bill number cannot be null");

        String sql = "UPDATE sales SET sale_mode = 'BILL', bill_number = ?, bill_sequence = ? " +
                "WHERE id = ? AND bill_number IS NULL;";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, billNumber.trim());
            ps.setInt(2, billSequence);
            ps.setLong(3, saleId);
            int rowsUpdated = ps.executeUpdate();
            if (rowsUpdated != 1) {
                throw new DatabaseException("Failed to assign bill number to sale id=" + saleId +
                        " (sale may not exist or already has a bill number)");
            }
        }
    }

    private Sale mapRowToSale(ResultSet rs) throws SQLException {
        long custIdVal = rs.getLong("customer_id");
        Long customerId = rs.wasNull() ? null : custIdVal;

        int billSeqVal = rs.getInt("bill_sequence");
        Integer billSequence = rs.wasNull() ? null : billSeqVal;

        return new Sale(
                rs.getLong("id"),
                SaleMode.valueOf(rs.getString("sale_mode")),
                rs.getString("financial_year"),
                rs.getString("bill_number"),
                billSequence,
                customerId,
                LocalDate.parse(rs.getString("sale_date")),
                rs.getString("created_at"),
                Money.ofPaise(rs.getLong("subtotal_paise")),
                Money.ofPaise(rs.getLong("discount_paise")),
                Money.ofPaise(rs.getLong("total_amount_paise")),
                rs.getLong("created_by_user_id"),
                List.of(),
                List.of()
        );
    }
}
