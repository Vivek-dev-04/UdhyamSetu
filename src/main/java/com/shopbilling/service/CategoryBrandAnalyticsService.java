package com.shopbilling.service;

import com.shopbilling.db.TransactionHelper;
import com.shopbilling.model.BrandPerformanceRow;
import com.shopbilling.model.CategoryBrandAnalyticsResult;
import com.shopbilling.model.CategoryPerformanceRow;
import com.shopbilling.model.FinancialYear;
import com.shopbilling.model.Money;
import com.shopbilling.model.Quarter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
 * Owner-only analytics service for Brand and Category contribution margins, sales velocity,
 * and return rates (PRD Section 2, Section 7.2 FR-7, Section 7.8 FR-37).
 * Pure service: strictly contains NO JavaFX imports.
 */
public class CategoryBrandAnalyticsService {

    private static final Logger logger = LoggerFactory.getLogger(CategoryBrandAnalyticsService.class);

    private final TransactionHelper transactionHelper;
    private final IstClock clock;

    public CategoryBrandAnalyticsService(TransactionHelper transactionHelper, IstClock clock) {
        this.transactionHelper = Objects.requireNonNull(transactionHelper, "TransactionHelper cannot be null");
        this.clock = Objects.requireNonNull(clock, "IstClock cannot be null");
    }

    /**
     * Generates Brand & Category analytics for an Indian Financial Year and optional Quarter.
     */
    public CategoryBrandAnalyticsResult generateAnalytics(FinancialYear fy, Quarter quarter, long userId) {
        Objects.requireNonNull(fy, "FinancialYear cannot be null");

        LocalDate from = quarter != null ? fy.getQuarterStartDate(quarter) : fy.getStartDate();
        LocalDate to = quarter != null ? fy.getQuarterEndDate(quarter) : fy.getEndDate();

        return generateAnalyticsInternal(fy, quarter, from, to, userId);
    }

    /**
     * Generates Brand & Category analytics for a custom date range.
     */
    public CategoryBrandAnalyticsResult generateAnalyticsForDateRange(LocalDate from, LocalDate to, long userId) {
        Objects.requireNonNull(from, "From date cannot be null");
        Objects.requireNonNull(to, "To date cannot be null");
        if (from.isAfter(to)) {
            throw new ValidationException("From date cannot be after to date");
        }

        FinancialYear fy = FinancialYear.ofDate(from);
        return generateAnalyticsInternal(fy, null, from, to, userId);
    }

    private CategoryBrandAnalyticsResult generateAnalyticsInternal(FinancialYear fy,
                                                                   Quarter quarter,
                                                                   LocalDate from,
                                                                   LocalDate to,
                                                                   long userId) {
        return transactionHelper.executeRead(conn -> {
            verifyOwner(conn, userId);

            // Fetch Brand performance
            List<BrandPerformanceRow> brandRows = queryBrandPerformance(conn, from, to);

            // Fetch Category performance
            List<CategoryPerformanceRow> categoryRows = queryCategoryPerformance(conn, from, to);

            long totalGrossPaise = 0L;
            long totalNetPaise = 0L;
            long totalProfitPaise = 0L;

            for (BrandPerformanceRow b : brandRows) {
                totalGrossPaise += b.grossSales().getPaise();
                totalNetPaise += b.netSales().getPaise();
                totalProfitPaise += b.grossProfit().getPaise();
            }

            // Compute contribution percentages if total profit > 0
            List<BrandPerformanceRow> enrichedBrandRows = new ArrayList<>();
            for (BrandPerformanceRow b : brandRows) {
                double contrib = totalProfitPaise > 0
                        ? ((double) b.grossProfit().getPaise() * 100.0 / (double) totalProfitPaise)
                        : 0.0;
                enrichedBrandRows.add(new BrandPerformanceRow(
                        b.brandId(), b.brandName(), b.unitsSold(), b.unitsReturned(),
                        b.grossSales(), b.returnsValue(), b.netSales(), b.cogs(), b.grossProfit(),
                        b.marginPercent(), b.returnRatePercent(), round2(contrib)
                ));
            }

            List<CategoryPerformanceRow> enrichedCategoryRows = new ArrayList<>();
            for (CategoryPerformanceRow c : categoryRows) {
                double contrib = totalProfitPaise > 0
                        ? ((double) c.grossProfit().getPaise() * 100.0 / (double) totalProfitPaise)
                        : 0.0;
                enrichedCategoryRows.add(new CategoryPerformanceRow(
                        c.categoryId(), c.categoryName(), c.unitsSold(), c.unitsReturned(),
                        c.grossSales(), c.returnsValue(), c.netSales(), c.cogs(), c.grossProfit(),
                        c.marginPercent(), c.returnRatePercent(), round2(contrib)
                ));
            }

            Money totalGross = Money.ofPaise(totalGrossPaise);
            Money totalNet = Money.ofPaise(totalNetPaise);
            Money totalProfit = Money.ofPaise(totalProfitPaise);

            logger.info("Generated Brand & Category analytics: from={} to={} brands={} categories={} netSales={} profit={}",
                    from, to, enrichedBrandRows.size(), enrichedCategoryRows.size(), totalNet, totalProfit);

            return new CategoryBrandAnalyticsResult(
                    fy, quarter, from, to, enrichedBrandRows, enrichedCategoryRows, totalGross, totalNet, totalProfit
            );
        });
    }

    private List<BrandPerformanceRow> queryBrandPerformance(Connection conn, LocalDate from, LocalDate to) throws SQLException {
        // 1. Sales by Brand
        String salesSql = "SELECT b.id, b.name, " +
                "COALESCE(SUM(si.quantity_scaled), 0) / 1000 as units_sold, " +
                "COALESCE(SUM(si.line_total_paise), 0) as gross_paise " +
                "FROM brands b " +
                "JOIN products p ON p.brand_id = b.id " +
                "JOIN product_variants pv ON pv.product_id = p.id " +
                "JOIN sale_items si ON si.variant_id = pv.id " +
                "JOIN sales s ON si.sale_id = s.id " +
                "WHERE s.sale_date BETWEEN ? AND ? " +
                "GROUP BY b.id, b.name ORDER BY gross_paise DESC;";

        Map<Long, BrandTemp> map = new HashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(salesSql)) {
            ps.setString(1, from.toString());
            ps.setString(2, to.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long id = rs.getLong("id");
                    String name = rs.getString("name");
                    int units = rs.getInt("units_sold");
                    long gross = rs.getLong("gross_paise");
                    map.put(id, new BrandTemp(id, name, units, gross, 0, 0L));
                }
            }
        }

        // 2. Returns by Brand
        String returnSql = "SELECT b.id, " +
                "COALESCE(SUM(ri.quantity_scaled), 0) / 1000 as units_returned, " +
                "COALESCE(SUM(ri.refund_paise), 0) as refund_paise " +
                "FROM brands b " +
                "JOIN products p ON p.brand_id = b.id " +
                "JOIN product_variants pv ON pv.product_id = p.id " +
                "JOIN return_items ri ON ri.variant_id = pv.id " +
                "JOIN returns r ON ri.return_id = r.id " +
                "WHERE r.return_date BETWEEN ? AND ? " +
                "GROUP BY b.id;";

        try (PreparedStatement ps = conn.prepareStatement(returnSql)) {
            ps.setString(1, from.toString());
            ps.setString(2, to.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long id = rs.getLong("id");
                    int unitsRet = rs.getInt("units_returned");
                    long refund = rs.getLong("refund_paise");
                    if (map.containsKey(id)) {
                        BrandTemp t = map.get(id);
                        map.put(id, new BrandTemp(t.id, t.name, t.unitsSold, t.grossPaise, unitsRet, refund));
                    }
                }
            }
        }

        // 3. Estimate COGS and Margins (Estimated purchase cost at 75% standard wholesale markup if no purchase rows)
        List<BrandPerformanceRow> result = new ArrayList<>();
        for (BrandTemp t : map.values()) {
            long netPaise = Math.max(0L, t.grossPaise - t.refundPaise);
            // Query weighted unit cost if available
            long cogsPaise = calculateBrandCogs(conn, t.id, t.unitsSold - t.unitsReturned, netPaise);
            long profitPaise = netPaise - cogsPaise;

            double margin = netPaise > 0 ? ((double) profitPaise * 100.0 / (double) netPaise) : 0.0;
            double returnRate = t.unitsSold > 0 ? ((double) t.unitsReturned * 100.0 / (double) t.unitsSold) : 0.0;

            result.add(new BrandPerformanceRow(
                    t.id, t.name, t.unitsSold, t.unitsReturned,
                    Money.ofPaise(t.grossPaise), Money.ofPaise(t.refundPaise),
                    Money.ofPaise(netPaise), Money.ofPaise(cogsPaise),
                    Money.ofPaise(profitPaise), round2(margin), round2(returnRate), 0.0
            ));
        }

        result.sort((a, b) -> b.netSales().compareTo(a.netSales()));
        return result;
    }

    private List<CategoryPerformanceRow> queryCategoryPerformance(Connection conn, LocalDate from, LocalDate to) throws SQLException {
        String salesSql = "SELECT c.id, c.name, " +
                "COALESCE(SUM(si.quantity_scaled), 0) / 1000 as units_sold, " +
                "COALESCE(SUM(si.line_total_paise), 0) as gross_paise " +
                "FROM categories c " +
                "JOIN products p ON p.category_id = c.id " +
                "JOIN product_variants pv ON pv.product_id = p.id " +
                "JOIN sale_items si ON si.variant_id = pv.id " +
                "JOIN sales s ON si.sale_id = s.id " +
                "WHERE s.sale_date BETWEEN ? AND ? " +
                "GROUP BY c.id, c.name ORDER BY gross_paise DESC;";

        Map<Long, CategoryTemp> map = new HashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(salesSql)) {
            ps.setString(1, from.toString());
            ps.setString(2, to.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long id = rs.getLong("id");
                    String name = rs.getString("name");
                    int units = rs.getInt("units_sold");
                    long gross = rs.getLong("gross_paise");
                    map.put(id, new CategoryTemp(id, name, units, gross, 0, 0L));
                }
            }
        }

        String returnSql = "SELECT c.id, " +
                "COALESCE(SUM(ri.quantity_scaled), 0) / 1000 as units_returned, " +
                "COALESCE(SUM(ri.refund_paise), 0) as refund_paise " +
                "FROM categories c " +
                "JOIN products p ON p.category_id = c.id " +
                "JOIN product_variants pv ON pv.product_id = p.id " +
                "JOIN return_items ri ON ri.variant_id = pv.id " +
                "JOIN returns r ON ri.return_id = r.id " +
                "WHERE r.return_date BETWEEN ? AND ? " +
                "GROUP BY c.id;";

        try (PreparedStatement ps = conn.prepareStatement(returnSql)) {
            ps.setString(1, from.toString());
            ps.setString(2, to.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long id = rs.getLong("id");
                    int unitsRet = rs.getInt("units_returned");
                    long refund = rs.getLong("refund_paise");
                    if (map.containsKey(id)) {
                        CategoryTemp t = map.get(id);
                        map.put(id, new CategoryTemp(t.id, t.name, t.unitsSold, t.grossPaise, unitsRet, refund));
                    }
                }
            }
        }

        List<CategoryPerformanceRow> result = new ArrayList<>();
        for (CategoryTemp t : map.values()) {
            long netPaise = Math.max(0L, t.grossPaise - t.refundPaise);
            long cogsPaise = calculateCategoryCogs(conn, t.id, t.unitsSold - t.unitsReturned, netPaise);
            long profitPaise = netPaise - cogsPaise;

            double margin = netPaise > 0 ? ((double) profitPaise * 100.0 / (double) netPaise) : 0.0;
            double returnRate = t.unitsSold > 0 ? ((double) t.unitsReturned * 100.0 / (double) t.unitsSold) : 0.0;

            result.add(new CategoryPerformanceRow(
                    t.id, t.name, t.unitsSold, t.unitsReturned,
                    Money.ofPaise(t.grossPaise), Money.ofPaise(t.refundPaise),
                    Money.ofPaise(netPaise), Money.ofPaise(cogsPaise),
                    Money.ofPaise(profitPaise), round2(margin), round2(returnRate), 0.0
            ));
        }

        result.sort((a, b) -> b.netSales().compareTo(a.netSales()));
        return result;
    }

    private long calculateBrandCogs(Connection conn, long brandId, int netUnits, long netSalesPaise) throws SQLException {
        String sql = "SELECT COALESCE(SUM(pi.cost_price_paise * (pi.quantity_scaled / 1000.0)), 0), " +
                "COALESCE(SUM(pi.quantity_scaled / 1000.0), 0) " +
                "FROM purchase_items pi " +
                "JOIN product_variants pv ON pi.variant_id = pv.id " +
                "JOIN products p ON pv.product_id = p.id " +
                "WHERE p.brand_id = ?;";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, brandId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    double totalCost = rs.getDouble(1);
                    double totalQty = rs.getDouble(2);
                    if (totalQty > 0) {
                        double avgUnitCost = totalCost / totalQty;
                        return Math.round(avgUnitCost * Math.max(0, netUnits));
                    }
                }
            }
        }
        // Fallback default electrical markup ~75% COGS
        return Math.round(netSalesPaise * 0.75);
    }

    private long calculateCategoryCogs(Connection conn, long categoryId, int netUnits, long netSalesPaise) throws SQLException {
        String sql = "SELECT COALESCE(SUM(pi.cost_price_paise * (pi.quantity_scaled / 1000.0)), 0), " +
                "COALESCE(SUM(pi.quantity_scaled / 1000.0), 0) " +
                "FROM purchase_items pi " +
                "JOIN product_variants pv ON pi.variant_id = pv.id " +
                "JOIN products p ON pv.product_id = p.id " +
                "WHERE p.category_id = ?;";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, categoryId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    double totalCost = rs.getDouble(1);
                    double totalQty = rs.getDouble(2);
                    if (totalQty > 0) {
                        double avgUnitCost = totalCost / totalQty;
                        return Math.round(avgUnitCost * Math.max(0, netUnits));
                    }
                }
            }
        }
        return Math.round(netSalesPaise * 0.75);
    }

    private void verifyOwner(Connection conn, long userId) throws SQLException {
        String sql = "SELECT role FROM users WHERE id = ?;";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && "OWNER".equalsIgnoreCase(rs.getString("role"))) {
                    return;
                }
            }
        }
        throw new ValidationException("Only the shop owner is authorized to view Brand & Category contribution margins");
    }

    private double round2(double val) {
        return BigDecimal.valueOf(val).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    /**
     * Exports analytics report to CSV format.
     */
    public void exportToCsv(CategoryBrandAnalyticsResult report, Writer writer) throws IOException {
        Objects.requireNonNull(report, "Result cannot be null");
        Objects.requireNonNull(writer, "Writer cannot be null");

        writer.write("--- BRAND PERFORMANCE & CONTRIBUTION MARGIN ---\r\n");
        writer.write(String.format("Period,%s to %s\r\n\r\n", report.fromDate(), report.toDate()));
        writer.write("Brand Name,Units Sold,Units Returned,Gross Sales (Rs),Returns (Rs),Net Sales (Rs),COGS (Rs),Gross Profit (Rs),Margin %,Return Rate %,Profit Contribution %\r\n");
        for (BrandPerformanceRow b : report.brandSummaries()) {
            writer.write(String.format("\"%s\",%d,%d,%s,%s,%s,%s,%s,%.2f%%,%.2f%%,%.2f%%\r\n",
                    b.brandName(), b.unitsSold(), b.unitsReturned(),
                    b.grossSales().toRupeesString(), b.returnsValue().toRupeesString(),
                    b.netSales().toRupeesString(), b.cogs().toRupeesString(),
                    b.grossProfit().toRupeesString(), b.marginPercent(),
                    b.returnRatePercent(), b.profitContributionPercent()));
        }

        writer.write("\r\n--- CATEGORY PERFORMANCE & RETURN RATE ---\r\n");
        writer.write("Category Name,Units Sold,Units Returned,Gross Sales (Rs),Returns (Rs),Net Sales (Rs),COGS (Rs),Gross Profit (Rs),Margin %,Return Rate %,Profit Contribution %\r\n");
        for (CategoryPerformanceRow c : report.categorySummaries()) {
            writer.write(String.format("\"%s\",%d,%d,%s,%s,%s,%s,%s,%.2f%%,%.2f%%,%.2f%%\r\n",
                    c.categoryName(), c.unitsSold(), c.unitsReturned(),
                    c.grossSales().toRupeesString(), c.returnsValue().toRupeesString(),
                    c.netSales().toRupeesString(), c.cogs().toRupeesString(),
                    c.grossProfit().toRupeesString(), c.marginPercent(),
                    c.returnRatePercent(), c.profitContributionPercent()));
        }

        writer.flush();
    }

    public String exportToCsvString(CategoryBrandAnalyticsResult report) {
        StringWriter sw = new StringWriter();
        try {
            exportToCsv(report, sw);
            return sw.toString();
        } catch (IOException e) {
            throw new RuntimeException("Failed to export analytics to CSV: " + e.getMessage(), e);
        }
    }

    private record BrandTemp(long id, String name, int unitsSold, long grossPaise, int unitsReturned, long refundPaise) {}
    private record CategoryTemp(long id, String name, int unitsSold, long grossPaise, int unitsReturned, long refundPaise) {}
}
