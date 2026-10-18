package com.shopbilling.service;

import com.shopbilling.db.*;
import com.shopbilling.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("StockAdjustmentService Tests (Manual Adjustments, Damage & Inventory Reconciliation)")
class StockAdjustmentServiceTest {

    @TempDir
    Path tempDir;

    private ConnectionFactory connectionFactory;
    private TransactionHelper transactionHelper;
    private StockRepository stockRepository;
    private ProductVariantRepository variantRepository;
    private StockService stockService;
    private UserRepository userRepository;
    private AuditLogService auditLogService;

    private StockAdjustmentService adjustmentService;

    private long ownerUserId;
    private long staffUserId;
    private long variant1Id;
    private long variant2Id;

    @BeforeEach
    void setUp() throws IOException {
        Path dbFile = tempDir.resolve("stock_adjustment_test.db");
        DatabaseConfig config = DatabaseConfig.forFile(dbFile);
        connectionFactory = new ConnectionFactory(config);
        new FlywayMigrator(connectionFactory).migrate();

        transactionHelper = new TransactionHelper(connectionFactory);
        stockRepository = new StockRepository();
        variantRepository = new ProductVariantRepository();
        SettingsRepository settingsRepository = new SettingsRepository();
        stockService = new StockService(transactionHelper, stockRepository, settingsRepository, variantRepository);
        userRepository = new UserRepository();
        AuditLogRepository auditLogRepository = new AuditLogRepository();
        auditLogService = new AuditLogService(transactionHelper, auditLogRepository, userRepository);

        adjustmentService = new StockAdjustmentService(
                transactionHelper,
                stockRepository,
                variantRepository,
                stockService,
                userRepository,
                auditLogService
        );

        BrandRepository brandRepository = new BrandRepository();
        CategoryRepository categoryRepository = new CategoryRepository();
        ProductRepository productRepository = new ProductRepository();

        transactionHelper.executeVoid(conn -> {
            User owner = userRepository.insert(conn, new User(null, "owner", "pass", UserRole.OWNER, "Shop Owner", true, null));
            User staff = userRepository.insert(conn, new User(null, "staff", "pass", UserRole.STAFF, "Counter Staff", true, null));
            ownerUserId = owner.id();
            staffUserId = staff.id();

            Brand brand = brandRepository.insert(conn, "Anchor");
            Category cat = categoryRepository.insert(conn, "Sockets");
            Product prod = productRepository.insert(conn, new Product(null, brand.id(), cat.id(), "Rider Socket", "PIECE", null, true, null));

            ProductVariant v1 = variantRepository.insert(conn, new ProductVariant(null, prod.id(), "6A 3-Pin", Money.ofRupees(60), Money.ofRupees(50), "BAR-V1", true, null));
            ProductVariant v2 = variantRepository.insert(conn, new ProductVariant(null, prod.id(), "16A 3-Pin", Money.ofRupees(120), Money.ofRupees(100), "BAR-V2", true, null));
            variant1Id = v1.id();
            variant2Id = v2.id();

            // Initial stock: v1 = 20 units, v2 = 15 units
            stockService.recordMovementInTransaction(conn, variant1Id, StockMovementType.PURCHASE, Quantity.of(20), "PURCHASE", 1L, "Init stock", ownerUserId);
            stockService.recordMovementInTransaction(conn, variant2Id, StockMovementType.PURCHASE, Quantity.of(15), "PURCHASE", 1L, "Init stock", ownerUserId);
        });
    }

    @Test
    @DisplayName("Owner adjusts stock to new target quantity with delta and audit log")
    void testAdjustStockSuccess() {
        StockAdjustmentResult result = adjustmentService.adjustStock(
                ownerUserId,
                variant1Id,
                Quantity.of(25),
                "Physical inventory count correction (+5)"
        );

        assertNotNull(result);
        assertEquals(variant1Id, result.variantId());
        assertEquals(Quantity.of(20), result.oldStock());
        assertEquals(Quantity.of(25), result.newStock());
        assertEquals(Quantity.of(5), result.delta());
        assertEquals(StockMovementType.ADJUSTMENT, result.movementType());

        // Verify stock in system is now 25
        assertEquals(Quantity.of(25), stockService.getCurrentStock(variant1Id));

        // Verify audit log
        List<AuditLogRecord> logs = auditLogService.getRecentLogs(ownerUserId, 5);
        assertTrue(logs.stream().anyMatch(l ->
                "STOCK_ADJUSTMENT".equals(l.action()) &&
                        "20".equals(l.oldValue()) &&
                        "25".equals(l.newValue())
        ));
    }

    @Test
    @DisplayName("Staff cannot perform stock adjustments (Owner RBAC)")
    void testStaffCannotAdjustStock() {
        assertThrows(UnauthorizedException.class, () ->
                adjustmentService.adjustStock(staffUserId, variant1Id, Quantity.of(30), "Staff adjustment attempt")
        );
    }

    @Test
    @DisplayName("Target stock matching current stock throws ValidationException")
    void testSameStockThrowsValidationException() {
        assertThrows(ValidationException.class, () ->
                adjustmentService.adjustStock(ownerUserId, variant1Id, Quantity.of(20), "No change")
        );
    }

    @Test
    @DisplayName("Write off damaged stock into quarantine bucket")
    void testWriteOffDamagedStock() {
        StockAdjustmentResult result = adjustmentService.writeOffDamagedStock(
                ownerUserId,
                variant2Id,
                Quantity.of(3),
                "Dropped and cracked porcelain casing during display setup"
        );

        assertNotNull(result);
        assertEquals(Quantity.of(15), result.oldStock());
        assertEquals(Quantity.of(12), result.newStock(), "15 - 3 = 12");
        assertEquals(Quantity.of(-3), result.delta());
        assertEquals(StockMovementType.DAMAGE, result.movementType());

        // Verify stock reduced
        assertEquals(Quantity.of(12), stockService.getCurrentStock(variant2Id));

        // Verify audit log recorded
        List<AuditLogRecord> logs = auditLogService.getRecentLogs(ownerUserId, 5);
        assertTrue(logs.stream().anyMatch(l -> "STOCK_DAMAGE".equals(l.action())));
    }

    @Test
    @DisplayName("Physical inventory reconciliation audits counts and adjusts discrepancies atomically")
    void testReconcilePhysicalInventory() {
        List<PhysicalCountItemInput> counts = List.of(
                new PhysicalCountItemInput(variant1Id, Quantity.of(20)), // Matches exactly
                new PhysicalCountItemInput(variant2Id, Quantity.of(10))  // Discrepancy: system has 15, physical is 10
        );

        PhysicalReconciliationSummary summary = adjustmentService.reconcilePhysicalInventory(
                ownerUserId,
                counts,
                "End of month physical count"
        );

        assertNotNull(summary);
        assertEquals(2, summary.totalAuditedItems());
        assertEquals(1, summary.matchedCount());
        assertEquals(1, summary.adjustedCount());
        assertEquals(1, summary.adjustments().size());

        StockAdjustmentResult adj = summary.adjustments().get(0);
        assertEquals(variant2Id, adj.variantId());
        assertEquals(Quantity.of(15), adj.oldStock());
        assertEquals(Quantity.of(10), adj.newStock());
        assertEquals(Quantity.of(-5), adj.delta());

        // Verify variant 2 stock is now 10
        assertEquals(Quantity.of(10), stockService.getCurrentStock(variant2Id));
        // Verify variant 1 stock remains 20
        assertEquals(Quantity.of(20), stockService.getCurrentStock(variant1Id));
    }
}
