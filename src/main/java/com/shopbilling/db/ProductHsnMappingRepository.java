package com.shopbilling.db;

import com.shopbilling.model.ClassificationStatus;
import com.shopbilling.model.ProductHsnMapping;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Parameterized SQLite repository for Product-to-HSN Classification Mappings & Approval States (Section 7).
 */
public class ProductHsnMappingRepository {

    public ProductHsnMapping saveMapping(Connection conn, ProductHsnMapping mapping) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        Objects.requireNonNull(mapping, "ProductHsnMapping cannot be null");

        // Deactivate previous active mappings for this product
        String deactivateSql = "UPDATE product_hsn_mapping SET is_active = 0, updated_at = datetime('now', '+05:30') WHERE product_id = ? AND is_active = 1;";
        try (PreparedStatement ps = conn.prepareStatement(deactivateSql)) {
            ps.setLong(1, mapping.productId());
            ps.executeUpdate();
        }

        String insertSql = "INSERT INTO product_hsn_mapping (product_id, hsn_code, status, matching_priority, match_reason, supporting_source, missing_specifications, verified_by_user_id, verified_at, rejection_reason, is_active, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, datetime('now', '+05:30'), datetime('now', '+05:30'));";

        try (PreparedStatement ps = conn.prepareStatement(insertSql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, mapping.productId());
            ps.setString(2, mapping.hsnCode());
            ps.setString(3, mapping.status() != null ? mapping.status().name() : ClassificationStatus.PENDING.name());
            ps.setInt(4, mapping.matchingPriority());
            ps.setString(5, mapping.matchReason());
            ps.setString(6, mapping.supportingSource());
            ps.setString(7, mapping.missingSpecifications());
            if (mapping.verifiedByUserId() != null) {
                ps.setLong(8, mapping.verifiedByUserId());
            } else {
                ps.setNull(8, Types.INTEGER);
            }
            ps.setString(9, mapping.verifiedAt());
            ps.setString(10, mapping.rejectionReason());
            ps.executeUpdate();

            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    long id = rs.getLong(1);
                    return findById(conn, id)
                            .orElseThrow(() -> new DatabaseException("Failed to retrieve saved product HSN mapping id=" + id));
                }
            }
        }
        throw new DatabaseException("Failed to save product HSN mapping");
    }

    public Optional<ProductHsnMapping> findById(Connection conn, long id) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        String sql = "SELECT id, product_id, hsn_code, status, matching_priority, match_reason, supporting_source, missing_specifications, verified_by_user_id, verified_at, rejection_reason, is_active, created_at, updated_at " +
                "FROM product_hsn_mapping WHERE id = ?;";
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

    public Optional<ProductHsnMapping> findActiveByProductId(Connection conn, long productId) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        String sql = "SELECT id, product_id, hsn_code, status, matching_priority, match_reason, supporting_source, missing_specifications, verified_by_user_id, verified_at, rejection_reason, is_active, created_at, updated_at " +
                "FROM product_hsn_mapping WHERE product_id = ? AND is_active = 1 LIMIT 1;";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, productId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
        }
        return Optional.empty();
    }

    public boolean updateStatus(Connection conn, long mappingId, ClassificationStatus newStatus, Long userId, String reason) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        Objects.requireNonNull(newStatus, "newStatus cannot be null");

        String sql = "UPDATE product_hsn_mapping SET status = ?, verified_by_user_id = ?, " +
                "verified_at = CASE WHEN ? = 'VERIFIED' THEN datetime('now', '+05:30') ELSE verified_at END, " +
                "rejection_reason = CASE WHEN ? = 'REJECTED' THEN ? ELSE rejection_reason END, " +
                "updated_at = datetime('now', '+05:30') WHERE id = ?;";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, newStatus.name());
            if (userId != null) {
                ps.setLong(2, userId);
            } else {
                ps.setNull(2, Types.INTEGER);
            }
            ps.setString(3, newStatus.name());
            ps.setString(4, newStatus.name());
            ps.setString(5, reason);
            ps.setLong(6, mappingId);
            return ps.executeUpdate() > 0;
        }
    }

    public List<ProductHsnMapping> findAll(Connection conn, ClassificationStatus statusFilter) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        String sql = statusFilter == null
                ? "SELECT id, product_id, hsn_code, status, matching_priority, match_reason, supporting_source, missing_specifications, verified_by_user_id, verified_at, rejection_reason, is_active, created_at, updated_at FROM product_hsn_mapping WHERE is_active = 1 ORDER BY id DESC;"
                : "SELECT id, product_id, hsn_code, status, matching_priority, match_reason, supporting_source, missing_specifications, verified_by_user_id, verified_at, rejection_reason, is_active, created_at, updated_at FROM product_hsn_mapping WHERE is_active = 1 AND status = ? ORDER BY id DESC;";

        List<ProductHsnMapping> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            if (statusFilter != null) {
                ps.setString(1, statusFilter.name());
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapRow(rs));
                }
            }
        }
        return list;
    }

    private ProductHsnMapping mapRow(ResultSet rs) throws SQLException {
        long uId = rs.getLong("verified_by_user_id");
        Long userVal = rs.wasNull() ? null : uId;

        return new ProductHsnMapping(
                rs.getLong("id"),
                rs.getLong("product_id"),
                rs.getString("hsn_code"),
                ClassificationStatus.fromString(rs.getString("status")),
                rs.getInt("matching_priority"),
                rs.getString("match_reason"),
                rs.getString("supporting_source"),
                rs.getString("missing_specifications"),
                userVal,
                rs.getString("verified_at"),
                rs.getString("rejection_reason"),
                rs.getInt("is_active") == 1,
                rs.getString("created_at"),
                rs.getString("updated_at")
        );
    }
}
