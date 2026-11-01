package com.shopbilling.db;

import com.shopbilling.model.HsnImportHistoryItem;
import com.shopbilling.model.HsnItem;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * Data Access Object interface for GST HSN Master Directory and Imports (Section 3).
 */
public interface HsnDAO {
    HsnItem insert(Connection conn, HsnItem item) throws SQLException;
    int[] batchUpsert(Connection conn, List<HsnItem> items) throws SQLException;
    boolean update(Connection conn, HsnItem item) throws SQLException;
    boolean delete(Connection conn, long id) throws SQLException;
    Optional<HsnItem> findById(Connection conn, long id) throws SQLException;
    Optional<HsnItem> findByCode(Connection conn, String hsnCode) throws SQLException;
    List<HsnItem> search(Connection conn, String query, int limit) throws SQLException;
    List<HsnItem> searchWithChapter(Connection conn, String query, String chapter, int limit) throws SQLException;
    List<HsnItem> findAll(Connection conn, int limit, int offset) throws SQLException;
    int count(Connection conn) throws SQLException;
    boolean isHsnInUse(Connection conn, String hsnCode) throws SQLException;
    long recordImportHistory(Connection conn, HsnImportHistoryItem historyItem) throws SQLException;
    List<HsnImportHistoryItem> getImportHistory(Connection conn, int limit) throws SQLException;
}
