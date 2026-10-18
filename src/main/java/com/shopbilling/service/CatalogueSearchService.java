package com.shopbilling.service;

import com.shopbilling.db.StockRepository;
import com.shopbilling.db.TransactionHelper;
import com.shopbilling.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

/**
 * High-performance catalogue search and barcode resolution service designed for
 * sub-second keyboard counter billing (PRD Section 7.2, 7.3, FR-8, Section 13).
 * Pure service: strictly contains NO JavaFX imports.
 */
public class CatalogueSearchService {

    private static final Logger logger = LoggerFactory.getLogger(CatalogueSearchService.class);

    private final TransactionHelper transactionHelper;
    private final StockRepository stockRepository;

    public CatalogueSearchService(TransactionHelper transactionHelper, StockRepository stockRepository) {
        this.transactionHelper = Objects.requireNonNull(transactionHelper, "TransactionHelper cannot be null");
        this.stockRepository = Objects.requireNonNull(stockRepository, "StockRepository cannot be null");
    }

    /**
     * Resolves a barcode scanner input to an active product variant in O(1) indexed time (FR-8).
     */
    public Optional<CatalogueSearchResult> findByBarcode(String barcode) {
        if (barcode == null || barcode.trim().isEmpty()) {
            return Optional.empty();
        }

        final String cleanBarcode = barcode.trim();
        return transactionHelper.executeRead(conn -> {
            String sql = "SELECT v.id AS variant_id, v.product_id, v.variant_name, v.mrp_paise, v.selling_price_paise, v.barcode, " +
                    "p.name AS product_name, p.unit_code, p.reorder_level_scaled, " +
                    "b.name AS brand_name, c.name AS category_name " +
                    "FROM product_variants v " +
                    "JOIN products p ON v.product_id = p.id " +
                    "JOIN brands b ON p.brand_id = b.id " +
                    "JOIN categories c ON p.category_id = c.id " +
                    "WHERE v.barcode = ? AND v.is_active = 1 AND p.is_active = 1;";

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, cleanBarcode);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(mapRowToResult(conn, rs, true));
                    }
                }
            }
            return Optional.empty();
        });
    }

    /**
     * Fast multi-token search across brand, category, product name, variant name, and barcode (FR-8).
     * Automatically prioritizes exact barcode matches.
     */
    public List<CatalogueSearchResult> searchCatalogue(String query, int limit) {
        final int maxLimit = Math.max(1, Math.min(limit, 100));

        if (query == null || query.trim().isEmpty()) {
            return List.of();
        }

        final String cleanQuery = query.trim();

        return transactionHelper.executeRead(conn -> {
            // First check if query is an exact barcode match
            Optional<CatalogueSearchResult> barcodeMatch = findByBarcode(cleanQuery);
            if (barcodeMatch.isPresent() && maxLimit == 1) {
                return List.of(barcodeMatch.get());
            }

            String[] tokens = cleanQuery.split("\\s+");
            StringBuilder sql = new StringBuilder();
            sql.append("SELECT v.id AS variant_id, v.product_id, v.variant_name, v.mrp_paise, v.selling_price_paise, v.barcode, ")
               .append("p.name AS product_name, p.unit_code, p.reorder_level_scaled, ")
               .append("b.name AS brand_name, c.name AS category_name ")
               .append("FROM product_variants v ")
               .append("JOIN products p ON v.product_id = p.id ")
               .append("JOIN brands b ON p.brand_id = b.id ")
               .append("JOIN categories c ON p.category_id = c.id ")
               .append("WHERE v.is_active = 1 AND p.is_active = 1 ");

            for (int i = 0; i < tokens.length; i++) {
                sql.append("AND (b.name LIKE ? OR c.name LIKE ? OR p.name LIKE ? OR v.variant_name LIKE ? OR v.barcode LIKE ?) ");
            }

            sql.append("ORDER BY b.name ASC, p.name ASC, v.variant_name ASC ")
               .append("LIMIT ?;");

            List<CatalogueSearchResult> results = new ArrayList<>();
            Set<Long> seenVariantIds = new HashSet<>();

            if (barcodeMatch.isPresent()) {
                results.add(barcodeMatch.get());
                seenVariantIds.add(barcodeMatch.get().variantId());
            }

            try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
                int paramIdx = 1;
                for (String token : tokens) {
                    String likePattern = "%" + token + "%";
                    for (int k = 0; k < 5; k++) {
                        ps.setString(paramIdx++, likePattern);
                    }
                }
                ps.setInt(paramIdx, maxLimit);

                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long varId = rs.getLong("variant_id");
                        if (!seenVariantIds.contains(varId) && results.size() < maxLimit) {
                            results.add(mapRowToResult(conn, rs, false));
                            seenVariantIds.add(varId);
                        }
                    }
                }
            }

            return results;
        });
    }

    /**
     * Retrieves all active variants belonging to a specific category, enriched with stock levels.
     */
    public List<CatalogueSearchResult> searchByCategory(long categoryId, int limit) {
        final int maxLimit = Math.max(1, Math.min(limit, 100));
        return transactionHelper.executeRead(conn -> {
            String sql = "SELECT v.id AS variant_id, v.product_id, v.variant_name, v.mrp_paise, v.selling_price_paise, v.barcode, " +
                    "p.name AS product_name, p.unit_code, p.reorder_level_scaled, " +
                    "b.name AS brand_name, c.name AS category_name " +
                    "FROM product_variants v " +
                    "JOIN products p ON v.product_id = p.id " +
                    "JOIN brands b ON p.brand_id = b.id " +
                    "JOIN categories c ON p.category_id = c.id " +
                    "WHERE c.id = ? AND v.is_active = 1 AND p.is_active = 1 " +
                    "ORDER BY b.name ASC, p.name ASC, v.variant_name ASC LIMIT ?;";

            List<CatalogueSearchResult> list = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, categoryId);
                ps.setInt(2, maxLimit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        list.add(mapRowToResult(conn, rs, false));
                    }
                }
            }
            return list;
        });
    }

    /**
     * Retrieves all active variants belonging to a specific brand, enriched with stock levels.
     */
    public List<CatalogueSearchResult> searchByBrand(long brandId, int limit) {
        final int maxLimit = Math.max(1, Math.min(limit, 100));
        return transactionHelper.executeRead(conn -> {
            String sql = "SELECT v.id AS variant_id, v.product_id, v.variant_name, v.mrp_paise, v.selling_price_paise, v.barcode, " +
                    "p.name AS product_name, p.unit_code, p.reorder_level_scaled, " +
                    "b.name AS brand_name, c.name AS category_name " +
                    "FROM product_variants v " +
                    "JOIN products p ON v.product_id = p.id " +
                    "JOIN brands b ON p.brand_id = b.id " +
                    "JOIN categories c ON p.category_id = c.id " +
                    "WHERE b.id = ? AND v.is_active = 1 AND p.is_active = 1 " +
                    "ORDER BY c.name ASC, p.name ASC, v.variant_name ASC LIMIT ?;";

            List<CatalogueSearchResult> list = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, brandId);
                ps.setInt(2, maxLimit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        list.add(mapRowToResult(conn, rs, false));
                    }
                }
            }
            return list;
        });
    }

    private CatalogueSearchResult mapRowToResult(Connection conn, ResultSet rs, boolean isBarcodeMatch) throws SQLException {
        long variantId = rs.getLong("variant_id");
        long productId = rs.getLong("product_id");
        String brandName = rs.getString("brand_name");
        String categoryName = rs.getString("category_name");
        String productName = rs.getString("product_name");
        String variantName = rs.getString("variant_name");
        String unitCode = rs.getString("unit_code");
        Money mrp = Money.ofPaise(rs.getLong("mrp_paise"));
        Money sellingPrice = Money.ofPaise(rs.getLong("selling_price_paise"));
        String barcode = rs.getString("barcode");

        long reorderScaled = rs.getLong("reorder_level_scaled");
        boolean hasReorder = !rs.wasNull();

        long stockScaled = stockRepository.getCurrentStockScaled(conn, variantId);
        Quantity currentStock = Quantity.ofScaled(stockScaled);

        boolean isLowStock = hasReorder && stockScaled <= reorderScaled;
        String fullName = String.format("%s %s %s", brandName, productName, variantName).trim();

        return new CatalogueSearchResult(
                variantId,
                productId,
                brandName,
                categoryName,
                productName,
                variantName,
                fullName,
                unitCode,
                mrp,
                sellingPrice,
                barcode,
                currentStock,
                isLowStock,
                isBarcodeMatch
        );
    }
}
