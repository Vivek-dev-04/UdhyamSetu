package com.shopbilling.db;

import com.shopbilling.model.CustomerType;
import com.shopbilling.model.Money;
import com.shopbilling.model.PaymentMode;
import com.shopbilling.model.SaleMode;
import com.shopbilling.model.SalesReportFilter;
import com.shopbilling.model.SalesSummaryRow;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Parameterized SQL repository for querying sales and return reports with dynamic combinable filters
 * (PRD Section 7.8, FR-33 - FR-36).
 */
public class ReportRepository {

    public List<SalesSummaryRow> querySales(Connection conn, SalesReportFilter filter) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        Objects.requireNonNull(filter, "Filter cannot be null");

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT DISTINCT s.id, s.sale_mode, s.bill_number, s.sale_date, s.customer_id, ")
                .append("c.name as customer_name, c.customer_type, ")
                .append("s.subtotal_paise, s.discount_paise, s.total_amount_paise, ")
                .append("(SELECT GROUP_CONCAT(DISTINCT p.payment_mode) FROM sale_payments p WHERE p.sale_id = s.id) as payment_modes ")
                .append("FROM sales s ")
                .append("LEFT JOIN customers c ON s.customer_id = c.id ");

        boolean needItemJoin = filter.brandId() != null || filter.categoryId() != null;
        if (needItemJoin) {
            sql.append("JOIN sale_items si ON s.id = si.sale_id ")
                    .append("JOIN product_variants pv ON si.variant_id = pv.id ")
                    .append("JOIN products pr ON pv.product_id = pr.id ");
        }

        if (filter.paymentMode() != null) {
            sql.append("JOIN sale_payments sp ON s.id = sp.sale_id ");
        }

        sql.append("WHERE 1=1 ");
        List<Object> params = new ArrayList<>();

        if (filter.dateFrom() != null) {
            sql.append("AND s.sale_date >= ? ");
            params.add(filter.dateFrom().toString());
        }
        if (filter.dateTo() != null) {
            sql.append("AND s.sale_date <= ? ");
            params.add(filter.dateTo().toString());
        }
        if (filter.saleMode() != null) {
            sql.append("AND s.sale_mode = ? ");
            params.add(filter.saleMode().name());
        }
        if (filter.paymentMode() != null) {
            sql.append("AND sp.payment_mode = ? ");
            params.add(filter.paymentMode().name());
        }
        if (filter.brandId() != null) {
            sql.append("AND pr.brand_id = ? ");
            params.add(filter.brandId());
        }
        if (filter.categoryId() != null) {
            sql.append("AND pr.category_id = ? ");
            params.add(filter.categoryId());
        }
        if (filter.customerType() != null) {
            sql.append("AND c.customer_type = ? ");
            params.add(filter.customerType().name());
        }
        if (filter.customerId() != null) {
            sql.append("AND s.customer_id = ? ");
            params.add(filter.customerId());
        }

        sql.append("ORDER BY s.sale_date DESC, s.id DESC;");

        List<SalesSummaryRow> results = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long custIdVal = rs.getLong("customer_id");
                    Long customerId = rs.wasNull() ? null : custIdVal;
                    String custName = rs.getString("customer_name");
                    String custTypeStr = rs.getString("customer_type");
                    CustomerType customerType = custTypeStr != null ? CustomerType.valueOf(custTypeStr) : null;
                    String modesStr = rs.getString("payment_modes");

                    results.add(new SalesSummaryRow(
                            rs.getLong("id"),
                            SaleMode.valueOf(rs.getString("sale_mode")),
                            rs.getString("bill_number"),
                            LocalDate.parse(rs.getString("sale_date")),
                            customerId,
                            custName != null ? custName : "Walk-in Customer",
                            customerType,
                            Money.ofPaise(rs.getLong("subtotal_paise")),
                            Money.ofPaise(rs.getLong("discount_paise")),
                            Money.ofPaise(rs.getLong("total_amount_paise")),
                            modesStr != null ? modesStr : ""
                    ));
                }
            }
        }
        return results;
    }

    public long getTotalReturnsInPeriod(Connection conn, LocalDate from, LocalDate to) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        StringBuilder sql = new StringBuilder("SELECT COALESCE(SUM(total_refund_paise), 0) FROM returns WHERE 1=1 ");
        List<String> params = new ArrayList<>();
        if (from != null) {
            sql.append("AND return_date >= ? ");
            params.add(from.toString());
        }
        if (to != null) {
            sql.append("AND return_date <= ? ");
            params.add(to.toString());
        }

        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setString(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        }
        return 0L;
    }

    public Map<PaymentMode, Money> getPaymentModeBreakdown(Connection conn, List<Long> saleIds) throws SQLException {
        Map<PaymentMode, Money> map = new EnumMap<>(PaymentMode.class);
        for (PaymentMode mode : PaymentMode.values()) {
            map.put(mode, Money.ZERO);
        }

        if (saleIds == null || saleIds.isEmpty()) {
            return map;
        }

        // SQLite has max variable limit, batch if needed or build parameter list
        StringBuilder sql = new StringBuilder("SELECT payment_mode, SUM(amount_paise) as mode_total FROM sale_payments WHERE sale_id IN (");
        for (int i = 0; i < saleIds.size(); i++) {
            sql.append(i == 0 ? "?" : ", ?");
        }
        sql.append(") GROUP BY payment_mode;");

        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < saleIds.size(); i++) {
                ps.setLong(i + 1, saleIds.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    PaymentMode mode = PaymentMode.valueOf(rs.getString("payment_mode"));
                    map.put(mode, Money.ofPaise(rs.getLong("mode_total")));
                }
            }
        }
        return map;
    }
}
