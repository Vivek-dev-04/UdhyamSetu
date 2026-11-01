package com.shopbilling.db;

import com.shopbilling.model.ProductGroup;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Parameterized SQLite repository for Product Groups (Section 9: Tally-inspired classification hierarchy).
 */
public class ProductGroupRepository {

    public ProductGroup insert(Connection conn, ProductGroup group) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        Objects.requireNonNull(group, "ProductGroup cannot be null");

        String sql = "INSERT INTO product_groups (parent_group_id, group_code, group_name, default_hsn_code, default_gst_rate, description, is_active, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, datetime('now', '+05:30'));";

        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            if (group.parentGroupId() != null) {
                ps.setLong(1, group.parentGroupId());
            } else {
                ps.setNull(1, Types.INTEGER);
            }
            ps.setString(2, group.groupCode());
            ps.setString(3, group.groupName());
            ps.setString(4, group.defaultHsnCode());
            ps.setDouble(5, group.defaultGstRate() != null ? group.defaultGstRate() : 18.0);
            ps.setString(6, group.description());
            ps.setInt(7, group.isActive() ? 1 : 0);
            ps.executeUpdate();

            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    long id = rs.getLong(1);
                    return findById(conn, id)
                            .orElseThrow(() -> new DatabaseException("Failed to retrieve created product group id=" + id));
                }
            }
        }
        throw new DatabaseException("Failed to insert product group, no generated key returned");
    }

    public boolean update(Connection conn, ProductGroup group) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        Objects.requireNonNull(group, "ProductGroup cannot be null");
        Objects.requireNonNull(group.id(), "Group ID cannot be null for update");

        String sql = "UPDATE product_groups SET parent_group_id = ?, group_code = ?, group_name = ?, " +
                "default_hsn_code = ?, default_gst_rate = ?, description = ?, is_active = ? WHERE id = ?;";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            if (group.parentGroupId() != null) {
                ps.setLong(1, group.parentGroupId());
            } else {
                ps.setNull(1, Types.INTEGER);
            }
            ps.setString(2, group.groupCode());
            ps.setString(3, group.groupName());
            ps.setString(4, group.defaultHsnCode());
            ps.setDouble(5, group.defaultGstRate() != null ? group.defaultGstRate() : 18.0);
            ps.setString(6, group.description());
            ps.setInt(7, group.isActive() ? 1 : 0);
            ps.setLong(8, group.id());
            return ps.executeUpdate() > 0;
        }
    }

    public boolean delete(Connection conn, long id) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        String sql = "DELETE FROM product_groups WHERE id = ?;";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            return ps.executeUpdate() > 0;
        }
    }

    public Optional<ProductGroup> findById(Connection conn, long id) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        String sql = "SELECT id, parent_group_id, group_code, group_name, default_hsn_code, default_gst_rate, description, is_active, created_at " +
                "FROM product_groups WHERE id = ?;";
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

    public Optional<ProductGroup> findByCode(Connection conn, String code) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        if (code == null || code.isBlank()) return Optional.empty();
        String sql = "SELECT id, parent_group_id, group_code, group_name, default_hsn_code, default_gst_rate, description, is_active, created_at " +
                "FROM product_groups WHERE group_code = ? COLLATE NOCASE;";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, code.trim().toUpperCase());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
        }
        return Optional.empty();
    }

    public List<ProductGroup> findAll(Connection conn, boolean activeOnly) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        String sql = activeOnly
                ? "SELECT id, parent_group_id, group_code, group_name, default_hsn_code, default_gst_rate, description, is_active, created_at FROM product_groups WHERE is_active = 1 ORDER BY group_name ASC;"
                : "SELECT id, parent_group_id, group_code, group_name, default_hsn_code, default_gst_rate, description, is_active, created_at FROM product_groups ORDER BY group_name ASC;";

        List<ProductGroup> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(mapRow(rs));
            }
        }
        return list;
    }

    private ProductGroup mapRow(ResultSet rs) throws SQLException {
        long pId = rs.getLong("parent_group_id");
        Long parentVal = rs.wasNull() ? null : pId;
        return new ProductGroup(
                rs.getLong("id"),
                parentVal,
                rs.getString("group_code"),
                rs.getString("group_name"),
                rs.getString("default_hsn_code"),
                rs.getDouble("default_gst_rate"),
                rs.getString("description"),
                rs.getInt("is_active") == 1,
                rs.getString("created_at")
        );
    }
}
