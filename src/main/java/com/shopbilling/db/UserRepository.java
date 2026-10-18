package com.shopbilling.db;

import com.shopbilling.model.User;
import com.shopbilling.model.UserRole;

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
 * Parameterized SQL repository for Users and Roles (PRD Section 5, 7.10, FR-41 - FR-43).
 */
public class UserRepository {

    public User insert(Connection conn, User user) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        Objects.requireNonNull(user, "User cannot be null");

        String sql = "INSERT INTO users (username, password_hash, role, full_name, is_active) " +
                "VALUES (?, ?, ?, ?, ?);";

        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, user.username().trim());
            ps.setString(2, user.passwordHash());
            ps.setString(3, user.role().name());
            ps.setString(4, user.fullName().trim());
            ps.setInt(5, user.isActive() ? 1 : 0);

            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    long id = rs.getLong(1);
                    return new User(
                            id,
                            user.username().trim(),
                            user.passwordHash(),
                            user.role(),
                            user.fullName().trim(),
                            user.isActive(),
                            null
                    );
                }
            }
        }
        throw new DatabaseException("Failed to insert user, no generated ID");
    }

    public Optional<User> findById(Connection conn, long id) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        String sql = "SELECT id, username, password_hash, role, full_name, is_active, created_at " +
                "FROM users WHERE id = ?;";

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

    public Optional<User> findByUsername(Connection conn, String username) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        if (username == null || username.trim().isEmpty()) {
            return Optional.empty();
        }

        String sql = "SELECT id, username, password_hash, role, full_name, is_active, created_at " +
                "FROM users WHERE LOWER(username) = LOWER(?);";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username.trim());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
        }
        return Optional.empty();
    }

    public List<User> findAll(Connection conn) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        String sql = "SELECT id, username, password_hash, role, full_name, is_active, created_at " +
                "FROM users ORDER BY id ASC;";

        List<User> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(mapRow(rs));
            }
        }
        return list;
    }

    public void updateProfile(Connection conn, long id, String fullName, UserRole role, boolean isActive) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        Objects.requireNonNull(fullName, "Full name cannot be null");
        Objects.requireNonNull(role, "UserRole cannot be null");

        String sql = "UPDATE users SET full_name = ?, role = ?, is_active = ? WHERE id = ?;";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, fullName.trim());
            ps.setString(2, role.name());
            ps.setInt(3, isActive ? 1 : 0);
            ps.setLong(4, id);
            ps.executeUpdate();
        }
    }

    public void updatePassword(Connection conn, long id, String passwordHash) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        Objects.requireNonNull(passwordHash, "Password hash cannot be null");

        String sql = "UPDATE users SET password_hash = ? WHERE id = ?;";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, passwordHash);
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    public int countUsers(Connection conn) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        String sql = "SELECT COUNT(*) FROM users;";
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return rs.getInt(1);
            }
        }
        return 0;
    }

    public int countActiveOwners(Connection conn) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        String sql = "SELECT COUNT(*) FROM users WHERE role = 'OWNER' AND is_active = 1;";
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return rs.getInt(1);
            }
        }
        return 0;
    }

    private User mapRow(ResultSet rs) throws SQLException {
        return new User(
                rs.getLong("id"),
                rs.getString("username"),
                rs.getString("password_hash"),
                UserRole.valueOf(rs.getString("role")),
                rs.getString("full_name"),
                rs.getInt("is_active") == 1,
                rs.getString("created_at")
        );
    }
}
