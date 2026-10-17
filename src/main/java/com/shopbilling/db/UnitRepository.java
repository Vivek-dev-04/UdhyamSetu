package com.shopbilling.db;

import com.shopbilling.model.Unit;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Parameterized SQL repository for Units of Measurement (PRD Section 7.2, FR-5).
 */
public class UnitRepository {

    public List<Unit> findAll(Connection conn) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        String sql = "SELECT code, name, allows_decimal FROM units ORDER BY code ASC;";
        List<Unit> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(mapRow(rs));
            }
        }
        return list;
    }

    public Optional<Unit> findByCode(Connection conn, String code) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        String sql = "SELECT code, name, allows_decimal FROM units WHERE code = ?;";
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

    private Unit mapRow(ResultSet rs) throws SQLException {
        return new Unit(
                rs.getString("code"),
                rs.getString("name"),
                rs.getInt("allows_decimal") == 1
        );
    }
}
