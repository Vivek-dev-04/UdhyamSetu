package com.shopbilling.service;

import com.shopbilling.db.BrandRepository;
import com.shopbilling.db.CategoryRepository;
import com.shopbilling.db.ConnectionFactory;
import com.shopbilling.db.DatabaseConfig;
import com.shopbilling.db.FlywayMigrator;
import com.shopbilling.db.ProductRepository;
import com.shopbilling.db.ProductVariantRepository;
import com.shopbilling.db.SettingsRepository;
import com.shopbilling.db.StockRepository;
import com.shopbilling.db.TransactionHelper;
import com.shopbilling.db.UnitRepository;
import com.shopbilling.model.Brand;
import com.shopbilling.model.Category;
import com.shopbilling.model.CurrentStock;
import com.shopbilling.model.Money;
import com.shopbilling.model.Product;
import com.shopbilling.model.ProductVariant;
import com.shopbilling.model.Quantity;
import com.shopbilling.model.StockMovement;
import com.shopbilling.model.StockMovementType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StockServiceTest {

    private StockService stockService;
    private ProductService productService;
    private BrandCategoryService brandCategoryService;

    private ProductVariant switchVariant;
    private ProductVariant wireVariant;
    private static final long OWNER_USER_ID = 1L;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws Exception {
        Path dbFile = tempDir.resolve("stock_test.db");
        DatabaseConfig config = DatabaseConfig.forFile(dbFile);
        ConnectionFactory connectionFactory = new ConnectionFactory(config);

        // Run migrations
        FlywayMigrator migrator = new FlywayMigrator(connectionFactory);
        migrator.migrate();

        // Seed owner user for created_by_user_id FK
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

        brandCategoryService = new BrandCategoryService(transactionHelper, brandRepo, catRepo);
        productService = new ProductService(transactionHelper, prodRepo, variantRepo, brandRepo, catRepo, unitRepo);
        stockService = new StockService(transactionHelper, stockRepo, settingsRepo, variantRepo);

        // Seed master data
        Brand brand = brandCategoryService.createBrand("Anchor");
        Category catSwitch = brandCategoryService.createCategory("Switch");
        Category catWire = brandCategoryService.createCategory("Wire");

        // Product with reorder level 10 pieces
        Product pSwitch = productService.createProduct(brand.id(), catSwitch.id(), "Anchor 1-Way Switch", "PIECE", Quantity.of(10));
        switchVariant = productService.addVariant(pSwitch.id(), "6A White", Money.ofRupees(50), Money.ofRupees(40), "BAR-SW-1");

        // Wire product with decimal support and reorder level 20.0 metres
        Product pWire = productService.createProduct(brand.id(), catWire.id(), "Anchor Wire 1.0 sq mm", "METRE", Quantity.parse("20.0"));
        wireVariant = productService.addVariant(pWire.id(), "Red", Money.ofRupees(20), Money.ofRupees(18), "BAR-WIRE-1");
    }

    @Test
    @DisplayName("Should record purchase and sale movements and derive accurate sum")
    void shouldDeriveStockFromMovements() {
        // Initially 0
        assertEquals(Quantity.ZERO, stockService.getCurrentStock(switchVariant.id()));

        // Purchase 50 pieces
        stockService.recordMovement(
                switchVariant.id(),
                StockMovementType.PURCHASE,
                Quantity.of(50),
                "PURCHASE",
                101L,
                "Distributor delivery",
                OWNER_USER_ID
        );
        assertEquals(Quantity.of(50), stockService.getCurrentStock(switchVariant.id()));

        // Sale 15 pieces (negative delta)
        stockService.recordMovement(
                switchVariant.id(),
                StockMovementType.SALE,
                Quantity.of(-15),
                "SALE",
                201L,
                "Retail counter sale",
                OWNER_USER_ID
        );
        assertEquals(Quantity.of(35), stockService.getCurrentStock(switchVariant.id()));

        // Return from customer (5 pieces back in)
        stockService.recordMovement(
                switchVariant.id(),
                StockMovementType.RETURN_IN,
                Quantity.of(5),
                "RETURN",
                301L,
                "Customer excess return",
                OWNER_USER_ID
        );
        assertEquals(Quantity.of(40), stockService.getCurrentStock(switchVariant.id()));
    }

    @Test
    @DisplayName("Should derive decimal stock accurately for wires sold by the metre")
    void shouldHandleDecimalStockMovements() {
        // Purchase 100.5 metres
        stockService.recordMovement(
                wireVariant.id(),
                StockMovementType.PURCHASE,
                Quantity.parse("100.5"),
                "PURCHASE",
                102L,
                "Coil delivery",
                OWNER_USER_ID
        );

        // Sale 23.25 metres
        stockService.recordMovement(
                wireVariant.id(),
                StockMovementType.SALE,
                Quantity.parse("-23.25"),
                "SALE",
                202L,
                "Cut wire sale",
                OWNER_USER_ID
        );

        // Current stock: 100.5 - 23.25 = 77.25 metres
        Quantity current = stockService.getCurrentStock(wireVariant.id());
        assertEquals("77.25", current.toDisplayString());
    }

    @Test
    @DisplayName("Should block negative stock by default when ALLOW_NEGATIVE_STOCK is false")
    void shouldBlockNegativeStockByDefault() {
        assertFalse(stockService.isNegativeStockAllowed());

        // Attempting to sell 5 pieces when stock is 0 must fail
        assertThrows(ValidationException.class, () ->
                stockService.recordMovement(
                        switchVariant.id(),
                        StockMovementType.SALE,
                        Quantity.of(-5),
                        "SALE",
                        999L,
                        "Sale with no stock",
                        OWNER_USER_ID
                )
        );

        assertEquals(Quantity.ZERO, stockService.getCurrentStock(switchVariant.id()));
    }

    @Test
    @DisplayName("Should permit negative stock when ALLOW_NEGATIVE_STOCK is true")
    void shouldPermitNegativeStockWhenConfigured() {
        stockService.setAllowNegativeStock(true, OWNER_USER_ID);
        assertTrue(stockService.isNegativeStockAllowed());

        // Selling with no stock allowed
        stockService.recordMovement(
                switchVariant.id(),
                StockMovementType.SALE,
                Quantity.of(-5),
                "SALE",
                999L,
                "Sale before stock entry",
                OWNER_USER_ID
        );

        Quantity stock = stockService.getCurrentStock(switchVariant.id());
        assertEquals("-5", stock.toDisplayString());
        assertTrue(stock.isNegative());
    }

    @Test
    @DisplayName("Should perform manual stock adjustment by computing delta")
    void shouldPerformManualStockAdjustment() {
        // Stock initially 0. Adjust to 25.
        stockService.adjustStock(switchVariant.id(), Quantity.of(25), "Initial physical count", OWNER_USER_ID);
        assertEquals(Quantity.of(25), stockService.getCurrentStock(switchVariant.id()));

        // Stock is 25. Adjust to 20 (shrinkage/breakage of 5).
        stockService.adjustStock(switchVariant.id(), Quantity.of(20), "Count audit correction", OWNER_USER_ID);
        assertEquals(Quantity.of(20), stockService.getCurrentStock(switchVariant.id()));

        List<StockMovement> movements = stockService.getMovements(switchVariant.id());
        assertEquals(2, movements.size());
        assertEquals(StockMovementType.ADJUSTMENT, movements.get(0).movementType());
        assertEquals(Quantity.of(-5), movements.get(0).quantity());
    }

    @Test
    @DisplayName("Should flag low stock when quantity is less than or equal to reorder level")
    void shouldFlagLowStock() {
        // Reorder level for switch is 10.
        stockService.recordMovement(switchVariant.id(), StockMovementType.PURCHASE, Quantity.of(15), "PURCHASE", 1L, null, OWNER_USER_ID);
        List<CurrentStock> lowStock1 = stockService.getLowStockItems();
        assertTrue(lowStock1.stream().noneMatch(cs -> cs.variantId() == switchVariant.id()));

        // Sale 6 -> stock becomes 9 (<= 10 reorder level)
        stockService.recordMovement(switchVariant.id(), StockMovementType.SALE, Quantity.of(-6), "SALE", 2L, null, OWNER_USER_ID);
        List<CurrentStock> lowStock2 = stockService.getLowStockItems();
        assertTrue(lowStock2.stream().anyMatch(cs -> cs.variantId() == switchVariant.id() && cs.isLowStock()));
    }
}
