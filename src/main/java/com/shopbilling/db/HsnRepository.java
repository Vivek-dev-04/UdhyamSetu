package com.shopbilling.db;

import com.shopbilling.model.HsnImportHistoryItem;
import com.shopbilling.model.HsnItem;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Parameterized SQLite repository and HsnDAO implementation for HSN Master records and Import Audits.
 * Adheres to Section 3 (hsn_master & hsn_import_history) & Section 5 (HSN search functionality).
 */
public class HsnRepository implements HsnDAO {

    @Override
    public HsnItem insert(Connection conn, HsnItem item) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        Objects.requireNonNull(item, "HsnItem cannot be null");

        String sql = "INSERT INTO hsn_master (hsn_code, description, product_type, chapter, heading, subheading, tariff_item, source, source_version, import_date, technical_notes, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, datetime('now', '+05:30'), ?, datetime('now', '+05:30'));";

        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, item.hsnCode());
            ps.setString(2, item.description());
            ps.setString(3, item.productType() != null ? item.productType() : "GOODS");
            ps.setString(4, item.chapter());
            ps.setString(5, item.heading());
            ps.setString(6, item.subheading());
            ps.setString(7, item.tariffItem());
            ps.setString(8, item.source());
            ps.setString(9, item.sourceVersion());
            ps.setString(10, item.technicalNotes());
            ps.executeUpdate();

            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    long id = rs.getLong(1);
                    return findById(conn, id)
                            .orElseThrow(() -> new DatabaseException("Failed to retrieve created HSN item id=" + id));
                }
            }
        }
        throw new DatabaseException("Failed to insert HSN item, no generated key returned");
    }

    @Override
    public int[] batchUpsert(Connection conn, List<HsnItem> items) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        if (items == null || items.isEmpty()) {
            return new int[0];
        }

        String sql = "INSERT INTO hsn_master (hsn_code, description, product_type, chapter, heading, subheading, tariff_item, source, source_version, import_date, technical_notes, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, datetime('now', '+05:30'), ?, datetime('now', '+05:30')) " +
                "ON CONFLICT(hsn_code) DO UPDATE SET " +
                "description = excluded.description, " +
                "product_type = coalesce(excluded.product_type, hsn_master.product_type), " +
                "chapter = coalesce(excluded.chapter, hsn_master.chapter), " +
                "heading = coalesce(excluded.heading, hsn_master.heading), " +
                "subheading = coalesce(excluded.subheading, hsn_master.subheading), " +
                "tariff_item = coalesce(excluded.tariff_item, hsn_master.tariff_item), " +
                "source = coalesce(excluded.source, hsn_master.source), " +
                "source_version = coalesce(excluded.source_version, hsn_master.source_version), " +
                "technical_notes = coalesce(excluded.technical_notes, hsn_master.technical_notes), " +
                "updated_at = datetime('now', '+05:30');";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (HsnItem item : items) {
                ps.setString(1, item.hsnCode());
                ps.setString(2, item.description());
                ps.setString(3, item.productType() != null ? item.productType() : "GOODS");
                ps.setString(4, item.chapter());
                ps.setString(5, item.heading());
                ps.setString(6, item.subheading());
                ps.setString(7, item.tariffItem());
                ps.setString(8, item.source());
                ps.setString(9, item.sourceVersion());
                ps.setString(10, item.technicalNotes());
                ps.addBatch();
            }
            return ps.executeBatch();
        }
    }

    @Override
    public boolean update(Connection conn, HsnItem item) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        Objects.requireNonNull(item, "HsnItem cannot be null");
        Objects.requireNonNull(item.id(), "HSN item ID cannot be null for update");

        String sql = "UPDATE hsn_master SET hsn_code = ?, description = ?, product_type = ?, " +
                "chapter = ?, heading = ?, subheading = ?, tariff_item = ?, source = ?, source_version = ?, technical_notes = ?, " +
                "updated_at = datetime('now', '+05:30') WHERE id = ?;";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, item.hsnCode());
            ps.setString(2, item.description());
            ps.setString(3, item.productType());
            ps.setString(4, item.chapter());
            ps.setString(5, item.heading());
            ps.setString(6, item.subheading());
            ps.setString(7, item.tariffItem());
            ps.setString(8, item.source());
            ps.setString(9, item.sourceVersion());
            ps.setString(10, item.technicalNotes());
            ps.setLong(11, item.id());
            return ps.executeUpdate() > 0;
        }
    }

    @Override
    public boolean delete(Connection conn, long id) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        String sql = "DELETE FROM hsn_master WHERE id = ?;";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            return ps.executeUpdate() > 0;
        }
    }

    @Override
    public Optional<HsnItem> findById(Connection conn, long id) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        String sql = "SELECT id, hsn_code, description, product_type, chapter, heading, subheading, tariff_item, source, source_version, import_date, technical_notes, updated_at " +
                "FROM hsn_master WHERE id = ?;";
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

    @Override
    public Optional<HsnItem> findByCode(Connection conn, String hsnCode) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        if (hsnCode == null || hsnCode.trim().isEmpty()) {
            return Optional.empty();
        }
        String sql = "SELECT id, hsn_code, description, product_type, chapter, heading, subheading, tariff_item, source, source_version, import_date, technical_notes, updated_at " +
                "FROM hsn_master WHERE hsn_code = ?;";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, HsnItem.cleanCode(hsnCode));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public List<HsnItem> search(Connection conn, String query, int limit) throws SQLException {
        return searchWithChapter(conn, query, null, limit);
    }

    @Override
    public List<HsnItem> searchWithChapter(Connection conn, String query, String chapter, int limit) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        if ((query == null || query.trim().isEmpty()) && (chapter == null || chapter.trim().isEmpty())) {
            return findAll(conn, limit > 0 ? limit : 20, 0);
        }

        String raw = query != null ? query.trim() : "";
        String cleaned = HsnItem.cleanCode(raw);
        String prefix = cleaned.isEmpty() ? raw + "%" : cleaned + "%";
        String containsCode = cleaned.isEmpty() ? "%" + raw + "%" : "%" + cleaned + "%";
        String containsDesc = "%" + raw + "%";
        int safeLimit = (limit <= 0 || limit > 100) ? 20 : limit;

        StringBuilder sql = new StringBuilder(
                "SELECT id, hsn_code, description, product_type, chapter, heading, subheading, tariff_item, source, source_version, import_date, technical_notes, updated_at, " +
                        "CASE " +
                        "  WHEN hsn_code = ? THEN 1 " +
                        "  WHEN hsn_code LIKE ? THEN 2 " +
                        "  WHEN hsn_code LIKE ? THEN 3 " +
                        "  WHEN description LIKE ? THEN 4 " +
                        "  ELSE 5 " +
                        "END AS match_rank " +
                        "FROM hsn_master " +
                        "WHERE (hsn_code = ? OR hsn_code LIKE ? OR hsn_code LIKE ? OR description LIKE ?) "
        );

        if (chapter != null && !chapter.isBlank()) {
            sql.append("AND chapter = ? ");
        }

        sql.append("ORDER BY match_rank ASC, LENGTH(hsn_code) ASC, hsn_code ASC LIMIT ?;");

        List<HsnItem> results = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int p = 1;
            ps.setString(p++, cleaned);
            ps.setString(p++, prefix);
            ps.setString(p++, containsCode);
            ps.setString(p++, containsDesc);

            ps.setString(p++, cleaned);
            ps.setString(p++, prefix);
            ps.setString(p++, containsCode);
            ps.setString(p++, containsDesc);

            if (chapter != null && !chapter.isBlank()) {
                ps.setString(p++, chapter.trim());
            }
            ps.setInt(p, safeLimit);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(mapRow(rs));
                }
            }
        }
        return results;
    }

    @Override
    public List<HsnItem> findAll(Connection conn, int limit, int offset) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        int safeLimit = limit <= 0 ? 50 : limit;
        int safeOffset = Math.max(0, offset);

        String sql = "SELECT id, hsn_code, description, product_type, chapter, heading, subheading, tariff_item, source, source_version, import_date, technical_notes, updated_at " +
                "FROM hsn_master ORDER BY hsn_code ASC LIMIT ? OFFSET ?;";

        List<HsnItem> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, safeLimit);
            ps.setInt(2, safeOffset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapRow(rs));
                }
            }
        }
        return list;
    }

    @Override
    public int count(Connection conn) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        String sql = "SELECT COUNT(*) FROM hsn_master;";
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return rs.getInt(1);
            }
        }
        return 0;
    }

    @Override
    public boolean isHsnInUse(Connection conn, String hsnCode) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        if (hsnCode == null || hsnCode.trim().isEmpty()) {
            return false;
        }
        String clean = HsnItem.cleanCode(hsnCode);

        // Check products
        String sqlProducts = "SELECT 1 FROM products WHERE hsn_code = ? LIMIT 1;";
        try (PreparedStatement ps = conn.prepareStatement(sqlProducts)) {
            ps.setString(1, clean);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return true;
                }
            }
        }

        // Check line items in historical sales
        String sqlSales = "SELECT 1 FROM sale_items WHERE hsn_code = ? LIMIT 1;";
        try (PreparedStatement ps = conn.prepareStatement(sqlSales)) {
            ps.setString(1, clean);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return true;
                }
            }
        }

        // Check product_hsn_mapping
        String sqlMap = "SELECT 1 FROM product_hsn_mapping WHERE hsn_code = ? LIMIT 1;";
        try (PreparedStatement ps = conn.prepareStatement(sqlMap)) {
            ps.setString(1, clean);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return true;
                }
            }
        }

        return false;
    }

    @Override
    public long recordImportHistory(Connection conn, HsnImportHistoryItem item) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        Objects.requireNonNull(item, "HsnImportHistoryItem cannot be null");

        String sql = "INSERT INTO hsn_import_history (file_name, file_hash, source_name, source_version, total_rows, imported_count, updated_count, skipped_count, status, imported_by_user_id, imported_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, datetime('now', '+05:30'));";

        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, item.fileName());
            ps.setString(2, item.fileHash());
            ps.setString(3, item.sourceName());
            ps.setString(4, item.sourceVersion());
            ps.setInt(5, item.totalRows());
            ps.setInt(6, item.importedCount());
            ps.setInt(7, item.updatedCount());
            ps.setInt(8, item.skippedCount());
            ps.setString(9, item.status());
            if (item.importedByUserId() != null) {
                ps.setLong(10, item.importedByUserId());
            } else {
                ps.setNull(10, Types.INTEGER);
            }
            ps.executeUpdate();

            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        }
        return -1;
    }

    @Override
    public List<HsnImportHistoryItem> getImportHistory(Connection conn, int limit) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        int safeLimit = limit <= 0 ? 20 : limit;
        String sql = "SELECT id, file_name, file_hash, source_name, source_version, total_rows, imported_count, updated_count, skipped_count, status, imported_by_user_id, imported_at " +
                "FROM hsn_import_history ORDER BY id DESC LIMIT ?;";

        List<HsnImportHistoryItem> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, safeLimit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long uId = rs.getLong("imported_by_user_id");
                    Long userId = rs.wasNull() ? null : uId;
                    list.add(new HsnImportHistoryItem(
                            rs.getLong("id"),
                            rs.getString("file_name"),
                            rs.getString("file_hash"),
                            rs.getString("source_name"),
                            rs.getString("source_version"),
                            rs.getInt("total_rows"),
                            rs.getInt("imported_count"),
                            rs.getInt("updated_count"),
                            rs.getInt("skipped_count"),
                            rs.getString("status"),
                            userId,
                            rs.getString("imported_at")
                    ));
                }
            }
        }
        return list;
    }

    private HsnItem mapRow(ResultSet rs) throws SQLException {
        return new HsnItem(
                rs.getLong("id"),
                rs.getString("hsn_code"),
                rs.getString("description"),
                rs.getString("product_type"),
                rs.getString("chapter"),
                rs.getString("heading"),
                rs.getString("subheading"),
                rs.getString("tariff_item"),
                rs.getString("source"),
                rs.getString("source_version"),
                rs.getString("import_date"),
                rs.getString("technical_notes"),
                rs.getString("updated_at")
        );
    }
}
