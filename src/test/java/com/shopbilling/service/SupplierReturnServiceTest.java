package com.shopbilling.service;

import com.shopbilling.db.*;
import com.shopbilling.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SupplierReturnService Tests (Distributor Returns & Debit Notes)")
class SupplierReturnServiceTest {

    @TempDir
    Path tempDir;

    private ConnectionFactory connectionFactory;
    private TransactionHelper transactionHelper;
    private SupplierRepository supplierRepository;
    private SupplierLedgerRepository supplierLedgerRepository;
    private ProductVariantRepository variantRepository;
    private StockRepository stockRepository;
    private StockService stockService;
    private UserRepository userRepository;
    private AuditLogService auditLogService;
    private IstClock clock;

    private SupplierReturnService supplierReturnService;

    private long ownerUserId;
    private long staffUserId;
    private long supplierId;
    private long variantId;

    @BeforeEach
    void setUp() throws IOException {
        Path dbFile = tempDir.resolve("supplier_return_test.db");
        DatabaseConfig config = DatabaseConfig.forFile(dbFile);
        connectionFactory = new ConnectionFactory(config);
        new FlywayMigrator(connectionFactory).migrate();

        transactionHelper = new TransactionHelper(connectionFactory);
        supplierRepository = new SupplierRepository();
        supplierLedgerRepository = new SupplierLedgerRepository();
        variantRepository = new ProductVariantRepository();
        stockRepository = new StockRepository();
        SettingsRepository settingsRepository = new SettingsRepository();
        stockService = new StockService(transactionHelper, stockRepository, settingsRepository, variantRepository);
        userRepository = new UserRepository();
        AuditLogRepository auditLogRepository = new AuditLogRepository();
        auditLogService = new AuditLogService(transactionHelper, auditLogRepository, userRepository);
        clock = IstClock.system();

        supplierReturnService = new SupplierReturnService(
                transactionHelper,
                supplierRepository,
                supplierLedgerRepository,
                variantRepository,
                stockService,
                userRepository,
                auditLogService,
                clock
        );

        BrandRepository brandRepository = new BrandRepository();
        CategoryRepository categoryRepository = new CategoryRepository();
        ProductRepository productRepository = new ProductRepository();

        transactionHelper.executeVoid(conn -> {
            User owner = userRepository.insert(conn, new User(null, "owner", "pass", UserRole.OWNER, "Shop Owner", true, null));
            User staff = userRepository.insert(conn, new User(null, "staff", "pass", UserRole.STAFF, "Counter Staff", true, null));
            ownerUserId = owner.id();
            staffUserId = staff.id();

            Supplier supplier = supplierRepository.insert(conn, new Supplier(
                    null, "Havells India Ltd", "9876543210", "Rajesh Sharma", "Delhi Depot", "07AAAAA0000A1Z5", "Primary distributor", null
            ));
            supplierId = supplier.id();

            Brand brand = brandRepository.insert(conn, "Havells");
            Category cat = categoryRepository.insert(conn, "Switches");
            Product prod = productRepository.insert(conn, new Product(null, brand.id(), cat.id(), "Fabio Switch", "PIECE", 10000L, true, null));
            ProductVariant var = variantRepository.insert(conn, new ProductVariant(null, prod.id(), "White 16A", Money.ofRupees(150), Money.ofRupees(120), "BAR-H1", true, null));
            variantId = var.id();

            // Seed initial stock: 50 units purchased from supplier
            stockService.recordMovementInTransaction(
                    conn,
                    variantId,
                    StockMovementType.PURCHASE,
                    Quantity.of(50),
                    "PURCHASE",
                    1L,
                    "Distributor stock-in",
                    ownerUserId
            );

            // Seed supplier credit: shop owes ₹4500 to distributor
            supplierLedgerRepository.insertEntry(
                    conn,
                    supplierId,
                    "PURCHASE_CREDIT",
                    450000L,
                    "PURCHASE",
                    1L,
                    clock.today().toString(),
                    "Purchase on credit",
                    ownerUserId
            );
        });
    }

    @Test
    @DisplayName("Happy path: Owner successfully returns items to distributor, deducting stock and reducing payable dues")
    void testProcessSupplierReturnSuccess() {
        SupplierReturnItemInput item = new SupplierReturnItemInput(
                variantId,
                Quantity.of(10),
                Money.ofRupees(90),
                "Defective internal spring mechanism"
        );

        SupplierReturnResult result = supplierReturnService.processSupplierReturn(
                ownerUserId,
                supplierId,
                clock.today(),
                List.of(item),
                "Batch #B42 defect return"
        );

        assertNotNull(result);
        assertTrue(result.debitNoteNumber().startsWith("DN"));
        assertEquals(supplierId, result.supplierId());
        assertEquals("Havells India Ltd", result.supplierName());
        assertEquals(Money.ofRupees(900), result.totalDebitAmount(), "10 units @ 90 = 900.00");
        assertEquals(1, result.items().size());
        assertEquals("White 16A", result.items().get(0).variantName());

        // Verify stock deducted: 50 - 10 = 40
        Quantity remainingStock = stockService.getCurrentStock(variantId);
        assertEquals(Quantity.of(40), remainingStock);

        // Verify supplier payable dues reduced: 4500 - 900 = 3600
        Money currentPayable = transactionHelper.executeRead(conn ->
                Money.ofPaise(supplierLedgerRepository.getCurrentPayablePaise(conn, supplierId)));
        assertEquals(Money.ofRupees(3600), currentPayable);

        // Verify audit log
        List<AuditLogRecord> logs = auditLogService.getRecentLogs(ownerUserId, 5);
        assertTrue(logs.stream().anyMatch(l ->
                "SUPPLIER_RETURN".equals(l.action()) &&
                        String.valueOf(supplierId).equals(l.entityId()) &&
                        l.newValue().contains(result.debitNoteNumber())
        ));
    }

    @Test
    @DisplayName("Staff user cannot process supplier returns (Owner RBAC)")
    void testStaffCannotProcessReturn() {
        SupplierReturnItemInput item = new SupplierReturnItemInput(
                variantId,
                Quantity.of(5),
                Money.ofRupees(90),
                "Defective item"
        );

        assertThrows(UnauthorizedException.class, () ->
                supplierReturnService.processSupplierReturn(
                        staffUserId,
                        supplierId,
                        clock.today(),
                        List.of(item),
                        "Unauthorized attempt"
                )
        );
    }

    @Test
    @DisplayName("Attempting return with empty items or non-existent supplier throws exception")
    void testValidationEdgeCases() {
        assertThrows(ValidationException.class, () ->
                supplierReturnService.processSupplierReturn(ownerUserId, supplierId, clock.today(), List.of(), "Empty")
        );

        SupplierReturnItemInput item = new SupplierReturnItemInput(
                variantId,
                Quantity.of(1),
                Money.ofRupees(90),
                "Defect"
        );

        assertThrows(EntityNotFoundException.class, () ->
                supplierReturnService.processSupplierReturn(ownerUserId, 99999L, clock.today(), List.of(item), "Bad supplier")
        );
    }

    @Test
    @DisplayName("Returning more stock than available throws ValidationException when negative stock is disallowed")
    void testNegativeStockBlocked() {
        SupplierReturnItemInput excessItem = new SupplierReturnItemInput(
                variantId,
                Quantity.of(100), // Only 50 in stock
                Money.ofRupees(90),
                "Over return"
        );

        assertThrows(ValidationException.class, () ->
                supplierReturnService.processSupplierReturn(ownerUserId, supplierId, clock.today(), List.of(excessItem), "Excess")
        );
    }
}
