package com.shopbilling.db;

import com.shopbilling.model.CurrentStock;
import com.shopbilling.model.Quantity;
import com.shopbilling.model.StockMovement;
import com.shopbilling.model.StockMovementType;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Append-only stock movements repository and derived stock calculator
 * (PRD Section 7.4, FR-15; Hard Rules 4 & 5).
 * Note: Contains NO update or delete methods.
 */
public class StockRepository {

    public StockMovement insertMovement(Connection conn, StockMovement movement) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        Objects.requireNonNull(movement, "StockMovement cannot be null");

        String sql = "INSERT INTO stock_movements (variant_id, movement_type, quantity_scaled, " +
                "reference_type, reference_id, reason, created_by_user_id) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?);";

        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, movement.variantId());
            ps.setString(2, movement.movementType().name());
            ps.setLong(3, movement.quantity().getScaledValue());
            ps.setString(4, movement.referenceType());
            ps.setLong(5, movement.referenceId());
            ps.setString(6, movement.reason());
            ps.setLong(7, movement.createdByUserId());
            ps.executeUpdate();

            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    long id = rs.getLong(1);
                    return new StockMovement(
                            id,
                            movement.variantId(),
                            movement.movementType(),
                            movement.quantity(),
                            movement.referenceType(),
                            movement.referenceId(),
                            movement.reason(),
                            null,
                            movement.createdByUserId()
                    );
                }
            }
        }
        throw new DatabaseException("Failed to insert stock movement, no generated key returned");
    }

    public long getCurrentStockScaled(Connection conn, long variantId) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        String sql = "SELECT COALESCE(SUM(quantity_scaled), 0) FROM stock_movements WHERE variant_id = ?;";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, variantId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        }
        return 0L;
    }

    public List<StockMovement> getMovementsForVariant(Connection conn, long variantId) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        String sql = "SELECT id, variant_id, movement_type, quantity_scaled, reference_type, " +
                "reference_id, reason, created_at, created_by_user_id " +
                "FROM stock_movements WHERE variant_id = ? ORDER BY id DESC;";

        List<StockMovement> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, variantId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new StockMovement(
                            rs.getLong("id"),
                            rs.getLong("variant_id"),
                            StockMovementType.fromString(rs.getString("movement_type")),
                            Quantity.ofScaled(rs.getLong("quantity_scaled")),
                            rs.getString("reference_type"),
                            rs.getLong("reference_id"),
                            rs.getString("reason"),
                            rs.getString("created_at"),
                            rs.getLong("created_by_user_id")
                    ));
                }
            }
        }
        return list;
    }

    public List<CurrentStock> getAllCurrentStock(Connection conn) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        String sql = "SELECT pv.id AS variant_id, pv.variant_name, p.id AS product_id, p.name AS product_name, " +
                "b.name AS brand_name, c.name AS category_name, p.unit_code, p.reorder_level_scaled, " +
                "COALESCE(SUM(sm.quantity_scaled), 0) AS current_stock_scaled " +
                "FROM product_variants pv " +
                "JOIN products p ON pv.product_id = p.id " +
                "JOIN brands b ON p.brand_id = b.id " +
                "JOIN categories c ON p.category_id = c.id " +
                "LEFT JOIN stock_movements sm ON pv.id = sm.variant_id " +
                "WHERE pv.is_active = 1 AND p.is_active = 1 " +
                "GROUP BY pv.id, pv.variant_name, p.id, p.name, b.name, c.name, p.unit_code, p.reorder_level_scaled " +
                "ORDER BY p.name ASC, pv.variant_name ASC;";

        List<CurrentStock> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                long currentScaled = rs.getLong("current_stock_scaled");
                long reorderScaled = rs.getLong("reorder_level_scaled");
                Quantity reorderLevel = rs.wasNull() ? null : Quantity.ofScaled(reorderScaled);
                Quantity currentStock = Quantity.ofScaled(currentScaled);

                boolean isLowStock = reorderLevel != null && currentStock.compareTo(reorderLevel) <= 0;

                list.add(new CurrentStock(
                        rs.getLong("variant_id"),
                        rs.getString("variant_name"),
                        rs.getLong("product_id"),
                        rs.getString("product_name"),
                        rs.getString("brand_name"),
                        rs.getString("category_name"),
                        rs.getString("unit_code"),
                        currentStock,
                        reorderLevel,
                        isLowStock
                ));
            }
        }
        return list;
    }
}
