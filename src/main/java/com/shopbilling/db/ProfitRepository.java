package com.shopbilling.db;

import com.shopbilling.model.Money;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Parameterized SQL repository for querying product costing, sales volume, and COGS calculations
 * (PRD Section 7.8, FR-37).
 */
public class ProfitRepository {

    public record VariantSalesSummary(
            long variantId,
            String productName,
            String variantName,
            String brandName,
            long grossQuantityScaled,
            long grossRevenuePaise
    ) {}

    public record VariantReturnSummary(
            long variantId,
            long returnedQuantityScaled,
            long refundPaise
    ) {}

    /**
     * Computes the weighted average purchase cost for each variant from all historic purchases:
     * sum(quantity_scaled * cost_price_paise) / sum(quantity_scaled).
     */
    public Map<Long, Money> getWeightedAverageUnitCosts(Connection conn) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        String sql = "SELECT variant_id, " +
                "SUM(CAST(quantity_scaled AS INTEGER) * CAST(cost_price_paise AS INTEGER)) AS total_weighted_paise, " +
                "SUM(quantity_scaled) AS total_qty_scaled " +
                "FROM purchase_items " +
                "GROUP BY variant_id;";

        Map<Long, Money> map = new HashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                long variantId = rs.getLong("variant_id");
                long totalWeightedPaise = rs.getLong("total_weighted_paise");
                long totalQtyScaled = rs.getLong("total_qty_scaled");
                if (totalQtyScaled > 0) {
                    long unitCostPaise = Math.round((double) totalWeightedPaise / (double) totalQtyScaled);
                    map.put(variantId, Money.ofPaise(unitCostPaise));
                }
            }
        }
        return map;
    }

    /**
     * Queries gross sales summary grouped by product variant for an optional date range.
     */
    public List<VariantSalesSummary> getVariantSales(Connection conn, LocalDate from, LocalDate to) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT pv.id AS variant_id, p.name AS product_name, pv.variant_name, b.name AS brand_name, ")
                .append("SUM(si.quantity_scaled) AS gross_qty_scaled, ")
                .append("SUM(si.line_total_paise) AS gross_revenue_paise ")
                .append("FROM sale_items si ")
                .append("JOIN sales s ON si.sale_id = s.id ")
                .append("JOIN product_variants pv ON si.variant_id = pv.id ")
                .append("JOIN products p ON pv.product_id = p.id ")
                .append("JOIN brands b ON p.brand_id = b.id ")
                .append("WHERE 1=1 ");

        List<String> params = new ArrayList<>();
        if (from != null) {
            sql.append("AND s.sale_date >= ? ");
            params.add(from.toString());
        }
        if (to != null) {
            sql.append("AND s.sale_date <= ? ");
            params.add(to.toString());
        }

        sql.append("GROUP BY pv.id, p.name, pv.variant_name, b.name ")
                .append("ORDER BY gross_revenue_paise DESC;");

        List<VariantSalesSummary> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setString(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new VariantSalesSummary(
                            rs.getLong("variant_id"),
                            rs.getString("product_name"),
                            rs.getString("variant_name"),
                            rs.getString("brand_name"),
                            rs.getLong("gross_qty_scaled"),
                            rs.getLong("gross_revenue_paise")
                    ));
                }
            }
        }
        return list;
    }

    /**
     * Queries returns grouped by product variant for an optional date range.
     */
    public Map<Long, VariantReturnSummary> getVariantReturns(Connection conn, LocalDate from, LocalDate to) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ri.variant_id, ")
                .append("SUM(ri.quantity_scaled) AS ret_qty_scaled, ")
                .append("SUM(ri.refund_paise) AS total_refund_paise ")
                .append("FROM return_items ri ")
                .append("JOIN returns r ON ri.return_id = r.id ")
                .append("WHERE 1=1 ");

        List<String> params = new ArrayList<>();
        if (from != null) {
            sql.append("AND r.return_date >= ? ");
            params.add(from.toString());
        }
        if (to != null) {
            sql.append("AND r.return_date <= ? ");
            params.add(to.toString());
        }

        sql.append("GROUP BY ri.variant_id;");

        Map<Long, VariantReturnSummary> map = new HashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setString(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long variantId = rs.getLong("variant_id");
                    map.put(variantId, new VariantReturnSummary(
                            variantId,
                            rs.getLong("ret_qty_scaled"),
                            rs.getLong("total_refund_paise")
                    ));
                }
            }
        }
        return map;
    }
}
