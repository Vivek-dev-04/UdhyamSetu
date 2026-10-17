package com.shopbilling.db;

import com.shopbilling.model.Customer;
import com.shopbilling.model.CustomerType;
import com.shopbilling.model.Money;

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
 * Parameterized SQL repository for Customers (PRD Section 7.3, FR-10 & Section 7.6, FR-26).
 */
public class CustomerRepository {

    public Customer insert(Connection conn, Customer customer) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        Objects.requireNonNull(customer, "Customer cannot be null");

        String sql = "INSERT INTO customers (name, phone, customer_type, address, credit_limit_paise, notes) " +
                "VALUES (?, ?, ?, ?, ?, ?);";

        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, customer.name());
            ps.setString(2, customer.phone());
            ps.setString(3, customer.customerType().name());
            ps.setString(4, customer.address());
            if (customer.creditLimit() != null) {
                ps.setLong(5, customer.creditLimit().getPaise());
            } else {
                ps.setNull(5, Types.INTEGER);
            }
            ps.setString(6, customer.notes());
            ps.executeUpdate();

            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    long id = rs.getLong(1);
                    return findById(conn, id)
                            .orElseThrow(() -> new DatabaseException("Failed to retrieve created customer id=" + id));
                }
            }
        }
        throw new DatabaseException("Failed to insert customer, no generated key returned");
    }

    public Optional<Customer> findById(Connection conn, long id) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        String sql = "SELECT id, name, phone, customer_type, address, credit_limit_paise, notes, created_at " +
                "FROM customers WHERE id = ?;";
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

    public List<Customer> search(Connection conn, String query) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        String term = "%" + query.trim() + "%";
        String sql = "SELECT id, name, phone, customer_type, address, credit_limit_paise, notes, created_at " +
                "FROM customers WHERE name LIKE ? COLLATE NOCASE OR phone LIKE ? ORDER BY name ASC LIMIT 50;";

        List<Customer> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, term);
            ps.setString(2, term);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapRow(rs));
                }
            }
        }
        return list;
    }

    public List<Customer> findAll(Connection conn) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        String sql = "SELECT id, name, phone, customer_type, address, credit_limit_paise, notes, created_at " +
                "FROM customers ORDER BY name COLLATE NOCASE ASC;";

        List<Customer> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(mapRow(rs));
            }
        }
        return list;
    }

    public List<Customer> findByType(Connection conn, CustomerType type) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        Objects.requireNonNull(type, "CustomerType cannot be null");
        String sql = "SELECT id, name, phone, customer_type, address, credit_limit_paise, notes, created_at " +
                "FROM customers WHERE customer_type = ? ORDER BY name COLLATE NOCASE ASC;";

        List<Customer> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, type.name());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapRow(rs));
                }
            }
        }
        return list;
    }

    public boolean update(Connection conn, Customer customer) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        Objects.requireNonNull(customer, "Customer cannot be null");
        Objects.requireNonNull(customer.id(), "Customer ID cannot be null for update");

        String sql = "UPDATE customers SET name = ?, phone = ?, customer_type = ?, address = ?, " +
                "credit_limit_paise = ?, notes = ? WHERE id = ?;";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, customer.name());
            ps.setString(2, customer.phone());
            ps.setString(3, customer.customerType().name());
            ps.setString(4, customer.address());
            if (customer.creditLimit() != null) {
                ps.setLong(5, customer.creditLimit().getPaise());
            } else {
                ps.setNull(5, Types.INTEGER);
            }
            ps.setString(6, customer.notes());
            ps.setLong(7, customer.id());

            return ps.executeUpdate() > 0;
        }
    }

    private Customer mapRow(ResultSet rs) throws SQLException {
        long creditLimitPaise = rs.getLong("credit_limit_paise");
        Money creditLimit = rs.wasNull() ? null : Money.ofPaise(creditLimitPaise);

        return new Customer(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("phone"),
                CustomerType.fromString(rs.getString("customer_type")),
                rs.getString("address"),
                creditLimit,
                rs.getString("notes"),
                rs.getString("created_at")
        );
    }
}
