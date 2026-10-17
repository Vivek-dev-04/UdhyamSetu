package com.shopbilling.db;

import com.shopbilling.model.Brand;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Hand-written parameterized SQL repository for Brands (PRD FR-4, FR-7).
 */
public class BrandRepository {

    public Brand insert(Connection conn, String name) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        Objects.requireNonNull(name, "Brand name cannot be null");

        String sql = "INSERT INTO brands (name) VALUES (?);";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name.trim());
            ps.executeUpdate();

            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    long id = rs.getLong(1);
                    return findById(conn, id)
                            .orElseThrow(() -> new DatabaseException("Failed to retrieve created brand id=" + id));
                }
            }
        }
        throw new DatabaseException("Failed to insert brand, no ID generated");
    }

    public Optional<Brand> findById(Connection conn, long id) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        String sql = "SELECT id, name, created_at FROM brands WHERE id = ?;";
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

    public Optional<Brand> findByName(Connection conn, String name) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        String sql = "SELECT id, name, created_at FROM brands WHERE LOWER(name) = LOWER(?);";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name.trim());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
        }
        return Optional.empty();
    }

    public List<Brand> findAll(Connection conn) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        String sql = "SELECT id, name, created_at FROM brands ORDER BY name COLLATE NOCASE ASC;";
        List<Brand> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(mapRow(rs));
            }
        }
        return list;
    }

    public boolean update(Connection conn, long id, String newName) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        Objects.requireNonNull(newName, "New brand name cannot be null");
        String sql = "UPDATE brands SET name = ? WHERE id = ?;";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, newName.trim());
            ps.setLong(2, id);
            return ps.executeUpdate() > 0;
        }
    }

    public boolean existsByName(Connection conn, String name, Long excludeId) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        String sql = excludeId == null
                ? "SELECT 1 FROM brands WHERE LOWER(name) = LOWER(?) LIMIT 1;"
                : "SELECT 1 FROM brands WHERE LOWER(name) = LOWER(?) AND id <> ? LIMIT 1;";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name.trim());
            if (excludeId != null) {
                ps.setLong(2, excludeId);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private Brand mapRow(ResultSet rs) throws SQLException {
        return new Brand(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("created_at")
        );
    }
}
