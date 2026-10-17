package com.shopbilling.db;

import com.shopbilling.model.Supplier;

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
 * Parameterized SQL repository for Suppliers (PRD Section 7.4, FR-16 & Section 7.6, FR-29).
 */
public class SupplierRepository {

    public Supplier insert(Connection conn, Supplier supplier) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        Objects.requireNonNull(supplier, "Supplier cannot be null");

        String sql = "INSERT INTO suppliers (name, phone, contact_person, address, gstin, notes) " +
                "VALUES (?, ?, ?, ?, ?, ?);";

        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, supplier.name());
            ps.setString(2, supplier.phone());
            ps.setString(3, supplier.contactPerson());
            ps.setString(4, supplier.address());
            ps.setString(5, supplier.gstin());
            ps.setString(6, supplier.notes());
            ps.executeUpdate();

            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    long id = rs.getLong(1);
                    return findById(conn, id)
                            .orElseThrow(() -> new DatabaseException("Failed to retrieve created supplier id=" + id));
                }
            }
        }
        throw new DatabaseException("Failed to insert supplier, no generated key returned");
    }

    public Optional<Supplier> findById(Connection conn, long id) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        String sql = "SELECT id, name, phone, contact_person, address, gstin, notes, created_at " +
                "FROM suppliers WHERE id = ?;";
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

    public List<Supplier> search(Connection conn, String query) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        String term = "%" + query.trim() + "%";
        String sql = "SELECT id, name, phone, contact_person, address, gstin, notes, created_at " +
                "FROM suppliers WHERE name LIKE ? COLLATE NOCASE OR phone LIKE ? OR contact_person LIKE ? COLLATE NOCASE " +
                "OR gstin LIKE ? COLLATE NOCASE ORDER BY name ASC LIMIT 50;";

        List<Supplier> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, term);
            ps.setString(2, term);
            ps.setString(3, term);
            ps.setString(4, term);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapRow(rs));
                }
            }
        }
        return list;
    }

    public List<Supplier> findAll(Connection conn) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        String sql = "SELECT id, name, phone, contact_person, address, gstin, notes, created_at " +
                "FROM suppliers ORDER BY name COLLATE NOCASE ASC;";

        List<Supplier> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(mapRow(rs));
            }
        }
        return list;
    }

    public boolean update(Connection conn, Supplier supplier) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        Objects.requireNonNull(supplier, "Supplier cannot be null");
        Objects.requireNonNull(supplier.id(), "Supplier ID cannot be null for update");

        String sql = "UPDATE suppliers SET name = ?, phone = ?, contact_person = ?, address = ?, " +
                "gstin = ?, notes = ? WHERE id = ?;";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, supplier.name());
            ps.setString(2, supplier.phone());
            ps.setString(3, supplier.contactPerson());
            ps.setString(4, supplier.address());
            ps.setString(5, supplier.gstin());
            ps.setString(6, supplier.notes());
            ps.setLong(7, supplier.id());

            return ps.executeUpdate() > 0;
        }
    }

    private Supplier mapRow(ResultSet rs) throws SQLException {
        return new Supplier(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("phone"),
                rs.getString("contact_person"),
                rs.getString("address"),
                rs.getString("gstin"),
                rs.getString("notes"),
                rs.getString("created_at")
        );
    }
}
