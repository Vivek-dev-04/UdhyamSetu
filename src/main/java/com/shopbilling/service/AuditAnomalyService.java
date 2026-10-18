package com.shopbilling.service;

import com.shopbilling.db.SettingsRepository;
import com.shopbilling.db.TransactionHelper;
import com.shopbilling.model.AnomalyCategory;
import com.shopbilling.model.AnomalySeverity;
import com.shopbilling.model.AuditAnomalyRecord;
import com.shopbilling.model.AuditAnomalyReport;
import com.shopbilling.model.Money;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Watchdog service analyzing operations, audit logs, and register closings
 * to detect irregularities and potential staff fraud (PRD Section 7.10 FR-44, Section 14).
 * Pure service: strictly contains NO JavaFX imports.
 */
public class AuditAnomalyService {

    private static final Logger logger = LoggerFactory.getLogger(AuditAnomalyService.class);

    private final TransactionHelper transactionHelper;
    private final SettingsRepository settingsRepository;
    private final IstClock clock;

    public AuditAnomalyService(TransactionHelper transactionHelper,
                               SettingsRepository settingsRepository,
                               IstClock clock) {
        this.transactionHelper = Objects.requireNonNull(transactionHelper, "TransactionHelper cannot be null");
        this.settingsRepository = Objects.requireNonNull(settingsRepository, "SettingsRepository cannot be null");
        this.clock = Objects.requireNonNull(clock, "IstClock cannot be null");
    }

    /**
     * Scans operational records and audit logs for anomalies within a date range (Owner-only).
     */
    public AuditAnomalyReport scanAnomalies(LocalDate from, LocalDate to, long requestingUserId) {
        Objects.requireNonNull(from, "From date cannot be null");
        Objects.requireNonNull(to, "To date cannot be null");
        if (from.isAfter(to)) {
            throw new ValidationException("From date cannot be after to date");
        }

        return transactionHelper.executeRead(conn -> {
            verifyOwner(conn, requestingUserId);

            List<AuditAnomalyRecord> anomalies = new ArrayList<>();

            // 1. Scan Cash Drawer Shortages at Day-End Closing
            anomalies.addAll(scanCashShortages(conn, from, to));

            // 2. Scan Staff Excessive Discounts
            anomalies.addAll(scanExcessiveDiscounts(conn, from, to));

            // 3. Scan Unusually High Manual Stock Write-offs / Damage
            anomalies.addAll(scanStockShrinkage(conn, from, to));

            // 4. Scan High-Value Returns Processed by Staff
            anomalies.addAll(scanHighValueStaffReturns(conn, from, to));

            // 5. Scan After-Hours Activity
            anomalies.addAll(scanAfterHoursActivity(conn, from, to));

            // Sort anomalies by timestamp DESC
            anomalies.sort((a, b) -> b.occurredAt().compareTo(a.occurredAt()));

            int highCount = (int) anomalies.stream().filter(a -> a.severity() == AnomalySeverity.HIGH).count();
            int medCount = (int) anomalies.stream().filter(a -> a.severity() == AnomalySeverity.MEDIUM).count();
            int lowCount = (int) anomalies.stream().filter(a -> a.severity() == AnomalySeverity.LOW).count();

            logger.info("Audit anomaly scan ({} to {}): total={} high={} medium={} low={}",
                    from, to, anomalies.size(), highCount, medCount, lowCount);

            return new AuditAnomalyReport(from, to, anomalies.size(), highCount, medCount, lowCount, anomalies);
        });
    }

    /**
     * Retrieves recent anomalies over the last 30 days up to a given limit.
     */
    public List<AuditAnomalyRecord> getRecentAnomalies(int limit, long requestingUserId) {
        LocalDate to = clock.today();
        LocalDate from = to.minusDays(30);
        AuditAnomalyReport report = scanAnomalies(from, to, requestingUserId);
        return report.anomalies().stream().limit(Math.max(1, limit)).toList();
    }

    private List<AuditAnomalyRecord> scanCashShortages(Connection conn, LocalDate from, LocalDate to) throws SQLException {
        String sql = "SELECT c.id, c.closing_date, c.expected_cash_paise, c.counted_cash_paise, " +
                "c.difference_paise, c.notes, c.closed_by_user_id, u.username, c.closed_at " +
                "FROM day_end_closings c " +
                "LEFT JOIN users u ON c.closed_by_user_id = u.id " +
                "WHERE c.closing_date BETWEEN ? AND ? AND c.difference_paise < 0;";

        List<AuditAnomalyRecord> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, from.toString());
            ps.setString(2, to.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long diffPaise = rs.getLong("difference_paise");
                    Money shortage = Money.ofPaise(Math.abs(diffPaise));
                    AnomalySeverity severity = shortage.getPaise() >= 50000 ? AnomalySeverity.HIGH : AnomalySeverity.MEDIUM;

                    list.add(new AuditAnomalyRecord(
                            "CS-" + rs.getLong("id"),
                            AnomalyCategory.CASH_DRAWER_SHORTAGE,
                            severity,
                            "Cash Drawer Shortage of " + shortage.toFormattedString(),
                            String.format("Day-end closing on %s recorded counted cash below expected cash by %s. Notes: %s",
                                    rs.getString("closing_date"), shortage.toFormattedString(),
                                    rs.getString("notes") != null ? rs.getString("notes") : "None"),
                            "DAY_END:" + rs.getLong("id"),
                            rs.getLong("closed_by_user_id"),
                            rs.getString("username"),
                            rs.getString("closed_at"),
                            shortage
                    ));
                }
            }
        }
        return list;
    }

    private List<AuditAnomalyRecord> scanExcessiveDiscounts(Connection conn, LocalDate from, LocalDate to) throws SQLException {
        String sql = "SELECT s.id, s.bill_number, s.subtotal_paise, s.discount_paise, " +
                "s.created_by_user_id, u.username, s.created_at " +
                "FROM sales s " +
                "JOIN users u ON s.created_by_user_id = u.id " +
                "WHERE s.sale_date BETWEEN ? AND ? AND s.discount_paise > 0 AND u.role = 'STAFF';";

        List<AuditAnomalyRecord> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, from.toString());
            ps.setString(2, to.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long subtotal = rs.getLong("subtotal_paise");
                    long discount = rs.getLong("discount_paise");
                    double discountPct = subtotal > 0 ? (discount * 100.0 / subtotal) : 0.0;

                    // Trigger if discount exceeds 10% or ₹500
                    if (discountPct > 10.0 || discount >= 50000) {
                        AnomalySeverity severity = (discountPct > 15.0 || discount >= 100000)
                                ? AnomalySeverity.HIGH : AnomalySeverity.MEDIUM;

                        list.add(new AuditAnomalyRecord(
                                "ED-" + rs.getLong("id"),
                                AnomalyCategory.EXCESSIVE_DISCOUNT,
                                severity,
                                String.format("Staff Discount of %.1f%% (%s)", discountPct, Money.ofPaise(discount).toFormattedString()),
                                String.format("Staff user '%s' applied a high discount of %s (%.1f%% of subtotal) on Sale %s",
                                        rs.getString("username"), Money.ofPaise(discount).toFormattedString(),
                                        discountPct, rs.getString("bill_number") != null ? rs.getString("bill_number") : ("#" + rs.getLong("id"))),
                                "SALE:" + rs.getLong("id"),
                                rs.getLong("created_by_user_id"),
                                rs.getString("username"),
                                rs.getString("created_at"),
                                Money.ofPaise(discount)
                        ));
                    }
                }
            }
        }
        return list;
    }

    private List<AuditAnomalyRecord> scanStockShrinkage(Connection conn, LocalDate from, LocalDate to) throws SQLException {
        String sql = "SELECT sm.id, sm.movement_type, sm.quantity_scaled, sm.reason, " +
                "sm.created_at, sm.created_by_user_id, u.username, pv.selling_price_paise, p.name as product_name " +
                "FROM stock_movements sm " +
                "JOIN users u ON sm.created_by_user_id = u.id " +
                "JOIN product_variants pv ON sm.variant_id = pv.id " +
                "JOIN products p ON pv.product_id = p.id " +
                "WHERE sm.movement_type IN ('DAMAGE', 'ADJUSTMENT') AND sm.quantity_scaled < 0 " +
                "AND DATE(sm.created_at) BETWEEN ? AND ?;";

        List<AuditAnomalyRecord> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, from.toString());
            ps.setString(2, to.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    double units = Math.abs(rs.getDouble("quantity_scaled") / 1000.0);
                    long unitPrice = rs.getLong("selling_price_paise");
                    long lossPaise = Math.round(units * unitPrice);
                    Money loss = Money.ofPaise(lossPaise);

                    if (lossPaise >= 100000) { // Loss >= ₹1,000
                        list.add(new AuditAnomalyRecord(
                                "SS-" + rs.getLong("id"),
                                AnomalyCategory.STOCK_SHRINKAGE,
                                lossPaise >= 500000 ? AnomalySeverity.HIGH : AnomalySeverity.MEDIUM,
                                String.format("Stock Loss: %.1f units of %s (%s)", units, rs.getString("product_name"), loss.toFormattedString()),
                                String.format("Manual stock reduction of %.1f units recorded as '%s' by '%s'. Reason: %s",
                                        units, rs.getString("movement_type"), rs.getString("username"),
                                        rs.getString("reason") != null ? rs.getString("reason") : "Unspecified"),
                                "STOCK_MOVEMENT:" + rs.getLong("id"),
                                rs.getLong("created_by_user_id"),
                                rs.getString("username"),
                                rs.getString("created_at"),
                                loss
                        ));
                    }
                }
            }
        }
        return list;
    }

    private List<AuditAnomalyRecord> scanHighValueStaffReturns(Connection conn, LocalDate from, LocalDate to) throws SQLException {
        String sql = "SELECT r.id, r.credit_note_number, r.total_refund_paise, r.notes, " +
                "r.created_by_user_id, u.username, r.created_at " +
                "FROM returns r " +
                "JOIN users u ON r.created_by_user_id = u.id " +
                "WHERE r.return_date BETWEEN ? AND ? AND r.total_refund_paise >= 200000 AND u.role = 'STAFF';";

        List<AuditAnomalyRecord> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, from.toString());
            ps.setString(2, to.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long refundPaise = rs.getLong("total_refund_paise");
                    Money refund = Money.ofPaise(refundPaise);

                    list.add(new AuditAnomalyRecord(
                            "HR-" + rs.getLong("id"),
                            AnomalyCategory.HIGH_VALUE_RETURN,
                            AnomalySeverity.HIGH,
                            "High-Value Staff Return of " + refund.toFormattedString(),
                            String.format("Staff user '%s' processed a high-value customer return (%s) without explicit owner role authorization. Notes: %s",
                                    rs.getString("username"), refund.toFormattedString(),
                                    rs.getString("notes") != null ? rs.getString("notes") : "None"),
                            "RETURN:" + rs.getLong("id"),
                            rs.getLong("created_by_user_id"),
                            rs.getString("username"),
                            rs.getString("created_at"),
                            refund
                    ));
                }
            }
        }
        return list;
    }

    private List<AuditAnomalyRecord> scanAfterHoursActivity(Connection conn, LocalDate from, LocalDate to) throws SQLException {
        String sql = "SELECT a.id, a.user_id, u.username, a.action, a.entity_name, a.reason, a.created_at " +
                "FROM audit_log a " +
                "LEFT JOIN users u ON a.user_id = u.id " +
                "WHERE DATE(a.created_at) BETWEEN ? AND ?;";

        List<AuditAnomalyRecord> list = new ArrayList<>();
        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, from.toString());
            ps.setString(2, to.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String created = rs.getString("created_at");
                    try {
                        LocalDateTime ldt = LocalDateTime.parse(created, dtf);
                        int hour = ldt.getHour();
                        // Shop hours typically 08:00 to 22:00. After 10 PM or before 7 AM is flagged
                        if (hour >= 22 || hour < 7) {
                            list.add(new AuditAnomalyRecord(
                                    "AH-" + rs.getLong("id"),
                                    AnomalyCategory.AFTER_HOURS_ACTIVITY,
                                    AnomalySeverity.MEDIUM,
                                    String.format("After-Hours Operation '%s' at %02d:%02d", rs.getString("action"), hour, ldt.getMinute()),
                                    String.format("User '%s' executed action '%s' on %s during shop closure hours (%s)",
                                            rs.getString("username"), rs.getString("action"), rs.getString("entity_name"), created),
                                    "AUDIT_LOG:" + rs.getLong("id"),
                                    rs.getLong("user_id"),
                                    rs.getString("username"),
                                    created,
                                    Money.ZERO
                            ));
                        }
                    } catch (Exception ignored) {}
                }
            }
        }
        return list;
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
        throw new ValidationException("Only the shop owner is authorized to view audit anomalies and fraud reports");
    }
}
