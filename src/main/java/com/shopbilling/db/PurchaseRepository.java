package com.shopbilling.db;

import com.shopbilling.model.Money;
import com.shopbilling.model.Purchase;
import com.shopbilling.model.PurchaseItem;
import com.shopbilling.model.Quantity;

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
 * Parameterized SQL repository for Purchases and Purchase Line Items (PRD Section 7.4, FR-16).
 */
public class PurchaseRepository {

    public Purchase insertPurchase(Connection conn, Purchase purchase) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        Objects.requireNonNull(purchase, "Purchase cannot be null");

        String sql = "INSERT INTO purchases (supplier_id, invoice_number, invoice_date, " +
                "total_amount_paise, paid_amount_paise, credit_amount_paise, notes, created_by_user_id) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?);";

        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, purchase.supplierId());
            ps.setString(2, purchase.invoiceNumber());
            ps.setString(3, purchase.invoiceDate().toString());
            ps.setLong(4, purchase.totalAmount().getPaise());
            ps.setLong(5, purchase.paidAmount().getPaise());
            ps.setLong(6, purchase.creditAmount().getPaise());
            ps.setString(7, purchase.notes());
            ps.setLong(8, purchase.createdByUserId());
            ps.executeUpdate();

            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    long id = rs.getLong(1);
                    return new Purchase(
                            id,
                            purchase.supplierId(),
                            purchase.invoiceNumber(),
                            purchase.invoiceDate(),
                            purchase.totalAmount(),
                            purchase.paidAmount(),
                            purchase.creditAmount(),
                            purchase.notes(),
                            null,
                            purchase.createdByUserId(),
                            purchase.items()
                    );
                }
            }
        }
        throw new DatabaseException("Failed to insert purchase record, no ID generated");
    }

    public PurchaseItem insertPurchaseItem(Connection conn, PurchaseItem item) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        Objects.requireNonNull(item, "PurchaseItem cannot be null");

        String sql = "INSERT INTO purchase_items (purchase_id, variant_id, quantity_scaled, " +
                "cost_price_paise, line_total_paise) " +
                "VALUES (?, ?, ?, ?, ?);";

        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, item.purchaseId());
            ps.setLong(2, item.variantId());
            ps.setLong(3, item.quantity().getScaledValue());
            ps.setLong(4, item.costPrice().getPaise());
            ps.setLong(5, item.lineTotal().getPaise());
            ps.executeUpdate();

            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    long id = rs.getLong(1);
                    return new PurchaseItem(
                            id,
                            item.purchaseId(),
                            item.variantId(),
                            item.quantity(),
                            item.costPrice(),
                            item.lineTotal()
                    );
                }
            }
        }
        throw new DatabaseException("Failed to insert purchase line item");
    }

    public Optional<Purchase> findById(Connection conn, long id) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        String sql = "SELECT id, supplier_id, invoice_number, invoice_date, total_amount_paise, " +
                "paid_amount_paise, credit_amount_paise, notes, created_at, created_by_user_id " +
                "FROM purchases WHERE id = ?;";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    List<PurchaseItem> items = findItemsByPurchaseId(conn, id);
                    return Optional.of(mapPurchase(rs, items));
                }
            }
        }
        return Optional.empty();
    }

    public List<PurchaseItem> findItemsByPurchaseId(Connection conn, long purchaseId) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        String sql = "SELECT id, purchase_id, variant_id, quantity_scaled, cost_price_paise, line_total_paise " +
                "FROM purchase_items WHERE purchase_id = ? ORDER BY id ASC;";

        List<PurchaseItem> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, purchaseId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new PurchaseItem(
                            rs.getLong("id"),
                            rs.getLong("purchase_id"),
                            rs.getLong("variant_id"),
                            Quantity.ofScaled(rs.getLong("quantity_scaled")),
                            Money.ofPaise(rs.getLong("cost_price_paise")),
                            Money.ofPaise(rs.getLong("line_total_paise"))
                    ));
                }
            }
        }
        return list;
    }

    public List<Purchase> findBySupplierId(Connection conn, long supplierId) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        String sql = "SELECT id, supplier_id, invoice_number, invoice_date, total_amount_paise, " +
                "paid_amount_paise, credit_amount_paise, notes, created_at, created_by_user_id " +
                "FROM purchases WHERE supplier_id = ? ORDER BY invoice_date DESC, id DESC;";

        List<Purchase> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, supplierId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long purchaseId = rs.getLong("id");
                    List<PurchaseItem> items = findItemsByPurchaseId(conn, purchaseId);
                    list.add(mapPurchase(rs, items));
                }
            }
        }
        return list;
    }

    public List<Purchase> findAll(Connection conn) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        String sql = "SELECT id, supplier_id, invoice_number, invoice_date, total_amount_paise, " +
                "paid_amount_paise, credit_amount_paise, notes, created_at, created_by_user_id " +
                "FROM purchases ORDER BY invoice_date DESC, id DESC;";

        List<Purchase> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                long purchaseId = rs.getLong("id");
                List<PurchaseItem> items = findItemsByPurchaseId(conn, purchaseId);
                list.add(mapPurchase(rs, items));
            }
        }
        return list;
    }

    private Purchase mapPurchase(ResultSet rs, List<PurchaseItem> items) throws SQLException {
        return new Purchase(
                rs.getLong("id"),
                rs.getLong("supplier_id"),
                rs.getString("invoice_number"),
                LocalDate.parse(rs.getString("invoice_date")),
                Money.ofPaise(rs.getLong("total_amount_paise")),
                Money.ofPaise(rs.getLong("paid_amount_paise")),
                Money.ofPaise(rs.getLong("credit_amount_paise")),
                rs.getString("notes"),
                rs.getString("created_at"),
                rs.getLong("created_by_user_id"),
                items
        );
    }
}
