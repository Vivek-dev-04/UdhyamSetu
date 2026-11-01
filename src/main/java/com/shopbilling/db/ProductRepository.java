package com.shopbilling.db;

import com.shopbilling.model.ClassificationStatus;
import com.shopbilling.model.Product;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Parameterized SQL repository for Products (PRD Section 7.2, FR-4 & FR-5).
 * Integrated with GST HSN code and GST tax rate (Section 7).
 */
public class ProductRepository {

    public Product insert(Connection conn, Product product) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        Objects.requireNonNull(product, "Product cannot be null");

        String sql = "INSERT INTO products (brand_id, category_id, name, unit_code, reorder_level_scaled, is_active, hsn_code, gst_rate, group_id, hsn_status) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?);";

        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, product.brandId());
            ps.setLong(2, product.categoryId());
            ps.setString(3, product.name());
            ps.setString(4, product.unitCode());
            if (product.reorderLevelScaled() != null) {
                ps.setLong(5, product.reorderLevelScaled());
            } else {
                ps.setNull(5, Types.INTEGER);
            }
            ps.setInt(6, product.isActive() ? 1 : 0);
            if (product.hsnCode() != null && !product.hsnCode().trim().isEmpty()) {
                ps.setString(7, product.hsnCode().trim());
            } else {
                ps.setNull(7, Types.VARCHAR);
            }
            if (product.gstRate() != null) {
                ps.setDouble(8, product.gstRate());
            } else {
                ps.setDouble(8, 18.0);
            }
            if (product.groupId() != null) {
                ps.setLong(9, product.groupId());
            } else {
                ps.setNull(9, Types.INTEGER);
            }
            ps.setString(10, product.hsnStatus() != null ? product.hsnStatus().name() : ClassificationStatus.PENDING.name());

            ps.executeUpdate();

            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    long id = rs.getLong(1);
                    return findById(conn, id)
                            .orElseThrow(() -> new DatabaseException("Failed to retrieve created product id=" + id));
                }
            }
        }
        throw new DatabaseException("Failed to insert product, no generated key returned");
    }

    public Optional<Product> findById(Connection conn, long id) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        String sql = "SELECT id, brand_id, category_id, name, unit_code, reorder_level_scaled, is_active, created_at, hsn_code, gst_rate, group_id, hsn_status " +
                "FROM products WHERE id = ?;";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
        }
        return Optional.empty();
    }

    public List<Product> findAll(Connection conn, boolean activeOnly) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        String sql = activeOnly
                ? "SELECT id, brand_id, category_id, name, unit_code, reorder_level_scaled, is_active, created_at, hsn_code, gst_rate, group_id, hsn_status " +
                "FROM products WHERE is_active = 1 ORDER BY name COLLATE NOCASE ASC;"
                : "SELECT id, brand_id, category_id, name, unit_code, reorder_level_scaled, is_active, created_at, hsn_code, gst_rate, group_id, hsn_status " +
                "FROM products ORDER BY name COLLATE NOCASE ASC;";

        List<Product> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(mapRow(rs));
            }
        }
        return list;
    }

    public List<Product> searchByName(Connection conn, String query) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        String sql = "SELECT id, brand_id, category_id, name, unit_code, reorder_level_scaled, is_active, created_at, hsn_code, gst_rate, group_id, hsn_status " +
                "FROM products WHERE name LIKE ? COLLATE NOCASE AND is_active = 1 ORDER BY name ASC LIMIT 50;";
        List<Product> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, "%" + query.trim() + "%");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapRow(rs));
                }
            }
        }
        return list;
    }

    public boolean update(Connection conn, Product product) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        Objects.requireNonNull(product, "Product cannot be null");
        Objects.requireNonNull(product.id(), "Product ID cannot be null for update");

        String sql = "UPDATE products SET brand_id = ?, category_id = ?, name = ?, unit_code = ?, " +
                "reorder_level_scaled = ?, is_active = ?, hsn_code = ?, gst_rate = ?, group_id = ?, hsn_status = ? WHERE id = ?;";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, product.brandId());
            ps.setLong(2, product.categoryId());
            ps.setString(3, product.name());
            ps.setString(4, product.unitCode());
            if (product.reorderLevelScaled() != null) {
                ps.setLong(5, product.reorderLevelScaled());
            } else {
                ps.setNull(5, Types.INTEGER);
            }
            ps.setInt(6, product.isActive() ? 1 : 0);
            if (product.hsnCode() != null && !product.hsnCode().trim().isEmpty()) {
                ps.setString(7, product.hsnCode().trim());
            } else {
                ps.setNull(7, Types.VARCHAR);
            }
            if (product.gstRate() != null) {
                ps.setDouble(8, product.gstRate());
            } else {
                ps.setDouble(8, 18.0);
            }
            if (product.groupId() != null) {
                ps.setLong(9, product.groupId());
            } else {
                ps.setNull(9, Types.INTEGER);
            }
            ps.setString(10, product.hsnStatus() != null ? product.hsnStatus().name() : ClassificationStatus.PENDING.name());
            ps.setLong(11, product.id());

            return ps.executeUpdate() > 0;
        }
    }

    public boolean existsByNameInBrandAndCategory(Connection conn, long brandId, long categoryId, String name, Long excludeId) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        String sql = excludeId == null
                ? "SELECT 1 FROM products WHERE brand_id = ? AND category_id = ? AND LOWER(name) = LOWER(?) LIMIT 1;"
                : "SELECT 1 FROM products WHERE brand_id = ? AND category_id = ? AND LOWER(name) = LOWER(?) AND id <> ? LIMIT 1;";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, brandId);
            ps.setLong(2, categoryId);
            ps.setString(3, name.trim());
            if (excludeId != null) {
                ps.setLong(4, excludeId);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private Product mapRow(ResultSet rs) throws SQLException {
        long reorderLevel = rs.getLong("reorder_level_scaled");
        Long reorderVal = rs.wasNull() ? null : reorderLevel;

        String hsnCode = rs.getString("hsn_code");
        double gstRate = rs.getDouble("gst_rate");
        Double gstVal = rs.wasNull() ? 18.0 : gstRate;

        long grpId = rs.getLong("group_id");
        Long groupVal = rs.wasNull() ? null : grpId;

        String statusStr = rs.getString("hsn_status");
        ClassificationStatus statusVal = ClassificationStatus.fromString(statusStr);

        return new Product(
                rs.getLong("id"),
                rs.getLong("brand_id"),
                rs.getLong("category_id"),
                rs.getString("name"),
                rs.getString("unit_code"),
                reorderVal,
                rs.getInt("is_active") == 1,
                rs.getString("created_at"),
                hsnCode,
                gstVal,
                groupVal,
                statusVal
        );
    }
}
