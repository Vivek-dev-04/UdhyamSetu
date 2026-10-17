package com.shopbilling.service;

import com.shopbilling.db.BrandRepository;
import com.shopbilling.db.CategoryRepository;
import com.shopbilling.db.ConnectionFactory;
import com.shopbilling.db.CustomerRepository;
import com.shopbilling.db.DatabaseConfig;
import com.shopbilling.db.FlywayMigrator;
import com.shopbilling.db.ProductRepository;
import com.shopbilling.db.ProductVariantRepository;
import com.shopbilling.db.PurchaseRepository;
import com.shopbilling.db.SettingsRepository;
import com.shopbilling.db.StockRepository;
import com.shopbilling.db.SupplierLedgerRepository;
import com.shopbilling.db.SupplierRepository;
import com.shopbilling.db.TransactionHelper;
import com.shopbilling.db.UnitRepository;
import com.shopbilling.model.Brand;
import com.shopbilling.model.Category;
import com.shopbilling.model.Money;
import com.shopbilling.model.Product;
import com.shopbilling.model.ProductVariant;
import com.shopbilling.model.Purchase;
import com.shopbilling.model.PurchaseItemInput;
import com.shopbilling.model.Quantity;
import com.shopbilling.model.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StockInServiceTest {

    private StockInService stockInService;
    private StockService stockService;
    private CustomerSupplierService customerSupplierService;
    private ProductService productService;
    private BrandCategoryService brandCategoryService;

    private Supplier supplier;
    private ProductVariant switchVariant;
    private ProductVariant wireVariant;
    private static final long OWNER_USER_ID = 1L;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws Exception {
        Path dbFile = tempDir.resolve("stock_in_test.db");
        DatabaseConfig config = DatabaseConfig.forFile(dbFile);
        ConnectionFactory connectionFactory = new ConnectionFactory(config);

        // Run migrations
        FlywayMigrator migrator = new FlywayMigrator(connectionFactory);
        migrator.migrate();

        // Seed user
        try (Connection conn = connectionFactory.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO users (id, username, password_hash, role, full_name) " +
                    "VALUES (1, 'owner', 'hash123', 'OWNER', 'Shop Owner');");
        }

        TransactionHelper transactionHelper = new TransactionHelper(connectionFactory);
        BrandRepository brandRepo = new BrandRepository();
        CategoryRepository catRepo = new CategoryRepository();
        UnitRepository unitRepo = new UnitRepository();
        ProductRepository prodRepo = new ProductRepository();
        ProductVariantRepository variantRepo = new ProductVariantRepository();
        StockRepository stockRepo = new StockRepository();
        SettingsRepository settingsRepo = new SettingsRepository();
        SupplierRepository supplierRepo = new SupplierRepository();
        CustomerRepository customerRepo = new CustomerRepository();
        SupplierLedgerRepository supplierLedgerRepo = new SupplierLedgerRepository();
        PurchaseRepository purchaseRepo = new PurchaseRepository();

        brandCategoryService = new BrandCategoryService(transactionHelper, brandRepo, catRepo);
        productService = new ProductService(transactionHelper, prodRepo, variantRepo, brandRepo, catRepo, unitRepo);
        stockService = new StockService(transactionHelper, stockRepo, settingsRepo, variantRepo);
        customerSupplierService = new CustomerSupplierService(transactionHelper, customerRepo, supplierRepo);
        stockInService = new StockInService(transactionHelper, purchaseRepo, stockService, supplierRepo, supplierLedgerRepo, variantRepo);

        // Seed supplier and catalogue
        supplier = customerSupplierService.createSupplier(
                "V-Guard Distributor Delhi",
                "9811002233",
                "Kapil Dev",
                "Chandni Chowk",
                "07AAAAA1111A1Z9",
                "Primary wire distributor"
        );

        Brand brand = brandCategoryService.createBrand("V-Guard");
        Category catSwitch = brandCategoryService.createCategory("Switch");
        Category catWire = brandCategoryService.createCategory("Wire");

        Product pSwitch = productService.createProduct(brand.id(), catSwitch.id(), "V-Guard Modular Switch", "PIECE", null);
        switchVariant = productService.addVariant(pSwitch.id(), "10A 1-Way", Money.ofRupees(60), Money.ofRupees(45), "BAR-VG-SW1");

        Product pWire = productService.createProduct(brand.id(), catWire.id(), "V-Guard Wire 2.5 sq mm", "METRE", null);
        wireVariant = productService.addVariant(pWire.id(), "Blue", Money.ofRupees(35), Money.ofRupees(28), "BAR-VG-W1");
    }

    @Test
    @DisplayName("Should record stock-in paid in full and atomically update stock movements")
    void shouldRecordStockInPaidInFull() {
        // 100 switches @ cost Rs 30 = Rs 3,000
        PurchaseItemInput item = new PurchaseItemInput(switchVariant.id(), Quantity.of(100), Money.ofRupees(30));

        Purchase purchase = stockInService.recordStockIn(
                supplier.id(),
                "INV-2026-001",
                LocalDate.now(),
                List.of(item),
                Money.ofRupees(3000), // Paid in full
                "Cash purchase delivery",
                OWNER_USER_ID
        );

        assertNotNull(purchase.id());
        assertEquals("INV-2026-001", purchase.invoiceNumber());
        assertEquals(Money.ofRupees(3000), purchase.totalAmount());
        assertEquals(Money.ofRupees(3000), purchase.paidAmount());
        assertEquals(Money.ZERO, purchase.creditAmount());

        // Derived stock must now be 100
        Quantity stock = stockService.getCurrentStock(switchVariant.id());
        assertEquals(Quantity.of(100), stock);

        // Supplier dues must be 0
        Money dues = stockInService.getSupplierPayableBalance(supplier.id());
        assertEquals(Money.ZERO, dues);
    }

    @Test
    @DisplayName("Should record partial payment stock-in and update supplier ledger dues")
    void shouldRecordStockInPartialCredit() {
        // 200 switches @ cost Rs 30 = Rs 6,000
        // 50.5 metres wire @ cost Rs 20 = Rs 1,010
        // Total = Rs 7,010
        PurchaseItemInput item1 = new PurchaseItemInput(switchVariant.id(), Quantity.of(200), Money.ofRupees(30));
        PurchaseItemInput item2 = new PurchaseItemInput(wireVariant.id(), Quantity.parse("50.5"), Money.ofRupees(20));

        Purchase purchase = stockInService.recordStockIn(
                supplier.id(),
                "INV-PARTIAL-88",
                LocalDate.now(),
                List.of(item1, item2),
                Money.ofRupees(2010), // Paid 2,010. Balance 5,000 on credit
                "Partial payment by UPI",
                OWNER_USER_ID
        );

        assertEquals(Money.ofRupees(7010), purchase.totalAmount());
        assertEquals(Money.ofRupees(2010), purchase.paidAmount());
        assertEquals(Money.ofRupees(5000), purchase.creditAmount());

        // Check stock
        assertEquals(Quantity.of(200), stockService.getCurrentStock(switchVariant.id()));
        assertEquals("50.5", stockService.getCurrentStock(wireVariant.id()).toDisplayString());

        // Supplier payable dues must equal Rs 5,000
        Money dues = stockInService.getSupplierPayableBalance(supplier.id());
        assertEquals(Money.ofRupees(5000), dues);
    }

    @Test
    @DisplayName("Should record 100% credit purchase and add full amount to supplier ledger")
    void shouldRecordFullCreditStockIn() {
        PurchaseItemInput item = new PurchaseItemInput(switchVariant.id(), Quantity.of(50), Money.ofRupees(30));

        Purchase purchase = stockInService.recordStockIn(
                supplier.id(),
                "INV-CREDIT-99",
                LocalDate.now(),
                List.of(item),
                Money.ZERO, // 100% on credit
                "30 days credit terms",
                OWNER_USER_ID
        );

        assertEquals(Money.ofRupees(1500), purchase.totalAmount());
        assertEquals(Money.ZERO, purchase.paidAmount());
        assertEquals(Money.ofRupees(1500), purchase.creditAmount());

        Money dues = stockInService.getSupplierPayableBalance(supplier.id());
        assertEquals(Money.ofRupees(1500), dues);
    }

    @Test
    @DisplayName("Should reject invalid paid amount exceeding total purchase")
    void shouldRejectPaidExceedingTotal() {
        PurchaseItemInput item = new PurchaseItemInput(switchVariant.id(), Quantity.of(10), Money.ofRupees(30)); // Rs 300

        assertThrows(ValidationException.class, () ->
                stockInService.recordStockIn(
                        supplier.id(),
                        "INV-OVERPAY",
                        LocalDate.now(),
                        List.of(item),
                        Money.ofRupees(500), // Paid 500 > 300 -> Invalid!
                        null,
                        OWNER_USER_ID
                )
        );
    }

    @Test
    @DisplayName("Atomic rollback: failed stock-in rolls back purchase, items, and movements")
    void shouldRollbackOnFailure() {
        PurchaseItemInput invalidItem = new PurchaseItemInput(99999L, Quantity.of(10), Money.ofRupees(10));

        assertThrows(EntityNotFoundException.class, () ->
                stockInService.recordStockIn(
                        supplier.id(),
                        "INV-FAIL",
                        LocalDate.now(),
                        List.of(invalidItem),
                        Money.ZERO,
                        null,
                        OWNER_USER_ID
                )
        );

        // Verify nothing was saved
        List<Purchase> purchases = stockInService.getAllPurchases();
        assertTrue(purchases.isEmpty());
        assertEquals(Money.ZERO, stockInService.getSupplierPayableBalance(supplier.id()));
    }
}
