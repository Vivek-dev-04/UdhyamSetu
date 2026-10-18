package com.shopbilling.service;

import com.shopbilling.db.ConnectionFactory;
import com.shopbilling.db.DatabaseConfig;
import com.shopbilling.db.FlywayMigrator;
import com.shopbilling.db.SettingsRepository;
import com.shopbilling.db.TransactionHelper;
import com.shopbilling.db.UserRepository;
import com.shopbilling.model.AnomalyCategory;
import com.shopbilling.model.AnomalySeverity;
import com.shopbilling.model.AuditAnomalyRecord;
import com.shopbilling.model.AuditAnomalyReport;
import com.shopbilling.model.Money;
import com.shopbilling.model.User;
import com.shopbilling.model.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("AuditAnomalyService Tests (Fraud Prevention & Watchdog Anomaly Detection)")
class AuditAnomalyServiceTest {

    @TempDir
    Path tempDir;

    private ConnectionFactory connectionFactory;
    private TransactionHelper transactionHelper;
    private SettingsRepository settingsRepository;
    private IstClock clock;

    private AuditAnomalyService anomalyService;

    private long ownerUserId;
    private long staffUserId;

    private final LocalDate testDate = LocalDate.of(2026, 6, 15);

    @BeforeEach
    void setUp() throws IOException {
        Path dbPath = tempDir.resolve("test_audit_anomaly.db");
        DatabaseConfig config = DatabaseConfig.forFile(dbPath);
        connectionFactory = new ConnectionFactory(config);
        new FlywayMigrator(connectionFactory).migrate();

        transactionHelper = new TransactionHelper(connectionFactory);
        settingsRepository = new SettingsRepository();
        clock = IstClock.fixedAtStartOfDay(testDate);

        UserRepository userRepository = new UserRepository();
        transactionHelper.executeVoid(conn -> {
            User owner = userRepository.insert(conn, new User(null, "owner", "passhash", UserRole.OWNER, "Shop Owner", true, null));
            User staff = userRepository.insert(conn, new User(null, "cashier1", "passhash", UserRole.STAFF, "Cashier One", true, null));
            ownerUserId = owner.id();
            staffUserId = staff.id();
        });

        anomalyService = new AuditAnomalyService(transactionHelper, settingsRepository, clock);
    }

    @Test
    @DisplayName("Should prevent non-owner staff from running anomaly audit scans")
    void testStaffAuthorizationDenied() {
        LocalDate today = clock.today();
        ValidationException ex = assertThrows(ValidationException.class, () ->
                anomalyService.scanAnomalies(today.minusDays(7), today, staffUserId)
        );
        assertTrue(ex.getMessage().contains("Only the shop owner is authorized"));
    }

    @Test
    @DisplayName("Should validate date range parameters")
    void testDateRangeValidation() {
        LocalDate today = clock.today();
        assertThrows(ValidationException.class, () ->
                anomalyService.scanAnomalies(today.plusDays(1), today, ownerUserId)
        );
        assertThrows(NullPointerException.class, () ->
                anomalyService.scanAnomalies(null, today, ownerUserId)
        );
    }

    @Test
    @DisplayName("Should detect cash drawer shortages at day-end closing")
    void testDetectCashDrawerShortage() throws SQLException {
        LocalDate today = clock.today();
        try (Connection conn = connectionFactory.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO day_end_closings (closing_date, expected_cash_paise, counted_cash_paise, difference_paise, notes, closed_by_user_id, closed_at) " +
                             "VALUES (?, 500000, 420000, -80000, 'Cash shortage in drawer', ?, ?)")) {
            ps.setString(1, today.toString());
            ps.setLong(2, staffUserId);
            ps.setString(3, today + " 21:30:00");
            ps.executeUpdate();
        }

        AuditAnomalyReport report = anomalyService.scanAnomalies(today.minusDays(1), today, ownerUserId);
        assertNotNull(report);
        assertEquals(1, report.totalAnomalies());
        assertEquals(1, report.highSeverityCount()); // Shortage >= ₹500 is HIGH

        AuditAnomalyRecord record = report.anomalies().getFirst();
        assertEquals(AnomalyCategory.CASH_DRAWER_SHORTAGE, record.category());
        assertEquals(AnomalySeverity.HIGH, record.severity());
        assertEquals(Money.ofPaise(80000), record.monetaryAmount());
        assertTrue(record.description().contains("Cash shortage"));
        assertEquals("cashier1", record.username());
    }

    @Test
    @DisplayName("Should detect excessive discounts applied by staff members")
    void testDetectExcessiveStaffDiscount() throws SQLException {
        LocalDate today = clock.today();
        try (Connection conn = connectionFactory.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO sales (sale_mode, financial_year, bill_number, sale_date, subtotal_paise, discount_paise, total_amount_paise, created_by_user_id, created_at) " +
                             "VALUES ('BILL', '2026-2027', 'INV-ANOMALY-1', ?, 200000, 40000, 160000, ?, ?)")) {
            ps.setString(1, today.toString());
            ps.setLong(2, staffUserId);
            ps.setString(3, today + " 15:00:00");
            ps.executeUpdate();
        }

        AuditAnomalyReport report = anomalyService.scanAnomalies(today.minusDays(1), today, ownerUserId);
        assertEquals(1, report.totalAnomalies());

        AuditAnomalyRecord record = report.anomalies().getFirst();
        assertEquals(AnomalyCategory.EXCESSIVE_DISCOUNT, record.category());
        assertEquals(AnomalySeverity.HIGH, record.severity()); // 20% discount > 15% threshold -> HIGH
        assertEquals(Money.ofPaise(40000), record.monetaryAmount());
        assertTrue(record.title().contains("20.0%"));
    }

    @Test
    @DisplayName("Should detect high stock shrinkage / damage write-offs")
    void testDetectStockShrinkageAnomaly() throws SQLException {
        LocalDate today = clock.today();

        long categoryId;
        long brandId;
        long productId;
        long variantId;

        try (Connection conn = connectionFactory.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO brands (name) VALUES ('Sony')")) {
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("SELECT id FROM brands LIMIT 1");
                 var rs = ps.executeQuery()) {
                rs.next();
                brandId = rs.getLong("id");
            }

            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO categories (name) VALUES ('Electronics')")) {
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("SELECT id FROM categories LIMIT 1");
                 var rs = ps.executeQuery()) {
                rs.next();
                categoryId = rs.getLong("id");
            }

            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO products (name, brand_id, category_id, unit_code, is_active) VALUES ('Smart TV', ?, ?, 'PIECE', 1)")) {
                ps.setLong(1, brandId);
                ps.setLong(2, categoryId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("SELECT id FROM products LIMIT 1");
                 var rs = ps.executeQuery()) {
                rs.next();
                productId = rs.getLong("id");
            }

            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO product_variants (product_id, variant_name, mrp_paise, selling_price_paise, is_active) " +
                            "VALUES (?, '55 inch', 2800000, 2500000, 1)")) {
                ps.setLong(1, productId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("SELECT id FROM product_variants LIMIT 1");
                 var rs = ps.executeQuery()) {
                rs.next();
                variantId = rs.getLong("id");
            }

            // Write-off 1 unit as DAMAGE (loss = ₹25,000)
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO stock_movements (variant_id, movement_type, quantity_scaled, reference_type, reference_id, reason, created_by_user_id, created_at) " +
                            "VALUES (?, 'DAMAGE', -1000, 'DAMAGE', 1, 'Display panel cracked', ?, ?)")) {
                ps.setLong(1, variantId);
                ps.setLong(2, staffUserId);
                ps.setString(3, today + " 17:00:00");
                ps.executeUpdate();
            }
        }

        AuditAnomalyReport report = anomalyService.scanAnomalies(today.minusDays(1), today, ownerUserId);
        assertEquals(1, report.totalAnomalies());

        AuditAnomalyRecord record = report.anomalies().getFirst();
        assertEquals(AnomalyCategory.STOCK_SHRINKAGE, record.category());
        assertEquals(AnomalySeverity.HIGH, record.severity());
        assertEquals(Money.ofPaise(2500000), record.monetaryAmount());
        assertTrue(record.description().contains("Display panel cracked"));
    }

    @Test
    @DisplayName("Should detect high-value customer returns processed by staff")
    void testDetectHighValueStaffReturns() throws SQLException {
        LocalDate today = clock.today();

        long saleId;
        try (Connection conn = connectionFactory.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO sales (sale_mode, financial_year, bill_number, sale_date, subtotal_paise, discount_paise, total_amount_paise, created_by_user_id, created_at) " +
                            "VALUES ('BILL', '2026-2027', 'INV-RET-1', ?, 500000, 0, 500000, ?, ?)")) {
                ps.setString(1, today.toString());
                ps.setLong(2, staffUserId);
                ps.setString(3, today + " 10:00:00");
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("SELECT id FROM sales WHERE bill_number = 'INV-RET-1'");
                 var rs = ps.executeQuery()) {
                rs.next();
                saleId = rs.getLong("id");
            }

            // High value return: ₹3,000 total refund processed by staff
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO returns (sale_id, credit_note_number, financial_year, return_date, refund_mode, total_refund_paise, notes, created_by_user_id, created_at) " +
                            "VALUES (?, 'CN-2026-999', '2026-2027', ?, 'CASH', 300000, 'Customer dissatisfied with appliance', ?, ?)")) {
                ps.setLong(1, saleId);
                ps.setString(2, today.toString());
                ps.setLong(3, staffUserId);
                ps.setString(4, today + " 18:30:00");
                ps.executeUpdate();
            }
        }

        AuditAnomalyReport report = anomalyService.scanAnomalies(today.minusDays(1), today, ownerUserId);
        assertEquals(1, report.totalAnomalies());

        AuditAnomalyRecord record = report.anomalies().getFirst();
        assertEquals(AnomalyCategory.HIGH_VALUE_RETURN, record.category());
        assertEquals(AnomalySeverity.HIGH, record.severity());
        assertEquals(Money.ofPaise(300000), record.monetaryAmount());
        assertTrue(record.description().contains("cashier1"));
    }

    @Test
    @DisplayName("Should detect operations performed during closed / after-hours")
    void testDetectAfterHoursActivity() throws SQLException {
        LocalDate today = clock.today();

        // Operation at 23:45 (after 10 PM)
        try (Connection conn = connectionFactory.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO audit_log (user_id, action, entity_name, entity_id, reason, created_at) " +
                             "VALUES (?, 'DELETE_DISCOUNT', 'SALE', '42', 'Modified sale discount late night', ?)")) {
            ps.setLong(1, staffUserId);
            ps.setString(2, today + " 23:45:00");
            ps.executeUpdate();
        }

        AuditAnomalyReport report = anomalyService.scanAnomalies(today.minusDays(1), today, ownerUserId);
        assertEquals(1, report.totalAnomalies());

        AuditAnomalyRecord record = report.anomalies().getFirst();
        assertEquals(AnomalyCategory.AFTER_HOURS_ACTIVITY, record.category());
        assertEquals(AnomalySeverity.MEDIUM, record.severity());
        assertTrue(record.title().contains("23:45"));
    }

    @Test
    @DisplayName("Should correctly sort and limit recent anomalies")
    void testGetRecentAnomaliesWithLimit() throws SQLException {
        LocalDate today = clock.today();

        try (Connection conn = connectionFactory.getConnection()) {
            // Insert 3 drawer shortage records on different days (unique constraint on closing_date)
            for (int i = 0; i < 3; i++) {
                LocalDate date = today.minusDays(i);
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO day_end_closings (closing_date, expected_cash_paise, counted_cash_paise, difference_paise, notes, closed_by_user_id, closed_at) " +
                                "VALUES (?, 500000, 480000, -20000, ?, ?, ?)")) {
                    ps.setString(1, date.toString());
                    ps.setString(2, "Shortage day " + i);
                    ps.setLong(3, staffUserId);
                    ps.setString(4, String.format("%s 20:%02d:00", date, 10 * (i + 1)));
                    ps.executeUpdate();
                }
            }
        }

        List<AuditAnomalyRecord> recent = anomalyService.getRecentAnomalies(2, ownerUserId);
        assertEquals(2, recent.size());
        // Verify sorted descending by timestamp
        assertTrue(recent.get(0).occurredAt().compareTo(recent.get(1).occurredAt()) >= 0);
    }
}
