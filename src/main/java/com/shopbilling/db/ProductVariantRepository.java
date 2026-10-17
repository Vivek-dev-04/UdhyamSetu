package com.shopbilling.db;

import com.shopbilling.model.Money;
import com.shopbilling.model.ProductVariant;

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
 * Parameterized SQL repository for Product Variants (PRD Section 7.2, FR-4 & FR-5).
 */
public class ProductVariantRepository {

    public ProductVariant insert(Connection conn, ProductVariant variant) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        Objects.requireNonNull(variant, "ProductVariant cannot be null");

        String sql = "INSERT INTO product_variants (product_id, variant_name, mrp_paise, selling_price_paise, barcode, is_active) " +
                "VALUES (?, ?, ?, ?, ?, ?);";

        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, variant.productId());
            ps.setString(2, variant.variantName());
            ps.setLong(3, variant.mrp().getPaise());
            ps.setLong(4, variant.sellingPrice().getPaise());
            if (variant.barcode() != null && !variant.barcode().isEmpty()) {
                ps.setString(5, variant.barcode());
            } else {
                ps.setNull(5, Types.VARCHAR);
            }
            ps.setInt(6, variant.isActive() ? 1 : 0);
            ps.executeUpdate();

            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    long id = rs.getLong(1);
                    return findById(conn, id)
                            .orElseThrow(() -> new DatabaseException("Failed to retrieve created variant id=" + id));
                }
            }
        }
        throw new DatabaseException("Failed to insert variant, no generated key returned");
    }

    public Optional<ProductVariant> findById(Connection conn, long id) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        String sql = "SELECT id, product_id, variant_name, mrp_paise, selling_price_paise, barcode, is_active, created_at " +
                "FROM product_variants WHERE id = ?;";
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

    public Optional<ProductVariant> findByBarcode(Connection conn, String barcode) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        if (barcode == null || barcode.trim().isEmpty()) {
            return Optional.empty();
        }

        String sql = "SELECT id, product_id, variant_name, mrp_paise, selling_price_paise, barcode, is_active, created_at " +
                "FROM product_variants WHERE barcode = ? AND is_active = 1;";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, barcode.trim());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
        }
        return Optional.empty();
    }

    public List<ProductVariant> findByProductId(Connection conn, long productId) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        String sql = "SELECT id, product_id, variant_name, mrp_paise, selling_price_paise, barcode, is_active, created_at " +
                "FROM product_variants WHERE product_id = ? ORDER BY id ASC;";
        List<ProductVariant> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, productId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapRow(rs));
                }
            }
        }
        return list;
    }

    public boolean update(Connection conn, ProductVariant variant) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        Objects.requireNonNull(variant, "ProductVariant cannot be null");
        Objects.requireNonNull(variant.id(), "Variant ID cannot be null for update");

        String sql = "UPDATE product_variants SET variant_name = ?, mrp_paise = ?, selling_price_paise = ?, " +
                "barcode = ?, is_active = ? WHERE id = ?;";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, variant.variantName());
            ps.setLong(2, variant.mrp().getPaise());
            ps.setLong(3, variant.sellingPrice().getPaise());
            if (variant.barcode() != null && !variant.barcode().isEmpty()) {
                ps.setString(4, variant.barcode());
            } else {
                ps.setNull(4, Types.VARCHAR);
            }
            ps.setInt(5, variant.isActive() ? 1 : 0);
            ps.setLong(6, variant.id());

            return ps.executeUpdate() > 0;
        }
    }

    public boolean existsByBarcode(Connection conn, String barcode, Long excludeId) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        if (barcode == null || barcode.trim().isEmpty()) {
            return false;
        }

        String sql = excludeId == null
                ? "SELECT 1 FROM product_variants WHERE barcode = ? LIMIT 1;"
                : "SELECT 1 FROM product_variants WHERE barcode = ? AND id <> ? LIMIT 1;";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, barcode.trim());
            if (excludeId != null) {
                ps.setLong(2, excludeId);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private ProductVariant mapRow(ResultSet rs) throws SQLException {
        return new ProductVariant(
                rs.getLong("id"),
                rs.getLong("product_id"),
                rs.getString("variant_name"),
                Money.ofPaise(rs.getLong("mrp_paise")),
                Money.ofPaise(rs.getLong("selling_price_paise")),
                rs.getString("barcode"),
                rs.getInt("is_active") == 1,
                rs.getString("created_at")
        );
    }
}
