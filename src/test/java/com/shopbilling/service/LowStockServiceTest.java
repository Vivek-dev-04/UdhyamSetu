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

@DisplayName("LowStockService Tests (Reorder Level Monitoring & Replenishment Planning)")
class LowStockServiceTest {

    @TempDir
    Path tempDir;

    private ConnectionFactory connectionFactory;
    private TransactionHelper transactionHelper;
    private StockRepository stockRepository;
    private ProductRepository productRepository;
    private ProductVariantRepository variantRepository;
    private BrandRepository brandRepository;
    private CategoryRepository categoryRepository;
    private LowStockService lowStockService;
    private StockService stockService;

    private long lowStockVariantId;
    private long healthyVariantId;

    @BeforeEach
    void setUp() throws IOException {
        Path dbFile = tempDir.resolve("low_stock_test.db");
        DatabaseConfig config = DatabaseConfig.forFile(dbFile);
        connectionFactory = new ConnectionFactory(config);
        new FlywayMigrator(connectionFactory).migrate();

        transactionHelper = new TransactionHelper(connectionFactory);
        stockRepository = new StockRepository();
        productRepository = new ProductRepository();
        variantRepository = new ProductVariantRepository();
        brandRepository = new BrandRepository();
        categoryRepository = new CategoryRepository();
        SettingsRepository settingsRepository = new SettingsRepository();

        lowStockService = new LowStockService(transactionHelper, stockRepository);
        stockService = new StockService(transactionHelper, stockRepository, settingsRepository, variantRepository);
        UserRepository userRepository = new UserRepository();

        transactionHelper.executeVoid(conn -> {
            User owner = userRepository.insert(conn, new User(null, "owner", "pass", UserRole.OWNER, "Shop Owner", true, null));
            long userId = owner.id();

            Brand havells = brandRepository.insert(conn, "Havells");
            Category switches = categoryRepository.insert(conn, "Switch");

            // Product 1 with reorder level = 10
            Product p1 = productRepository.insert(conn, new Product(null, havells.id(), switches.id(), "Fabio Switch", "PIECE", Quantity.of(10).getScaledValue(), true, null));
            ProductVariant v1 = variantRepository.insert(conn, new ProductVariant(null, p1.id(), "White 16A", Money.ofRupees(120), Money.ofRupees(100), "BAR1", true, null));
            ProductVariant v2 = variantRepository.insert(conn, new ProductVariant(null, p1.id(), "Black 16A", Money.ofRupees(130), Money.ofRupees(110), "BAR2", true, null));

            lowStockVariantId = v1.id();
            healthyVariantId = v2.id();

            // Product 2 without reorder level
            Product p2 = productRepository.insert(conn, new Product(null, havells.id(), switches.id(), "Fancy Plate", "PIECE", null, true, null));
            variantRepository.insert(conn, new ProductVariant(null, p2.id(), "2-Module", Money.ofRupees(80), Money.ofRupees(70), "BAR3", true, null));

            // Seed stock: v1 = 4 units (<= 10, low stock), v2 = 25 units (> 10, healthy)
            stockService.recordMovementInTransaction(conn, lowStockVariantId, StockMovementType.PURCHASE, Quantity.of(4), "PURCHASE", 1L, "Initial stock", userId);
            stockService.recordMovementInTransaction(conn, healthyVariantId, StockMovementType.PURCHASE, Quantity.of(25), "PURCHASE", 1L, "Initial stock", userId);
        });
    }

    @Test
    @DisplayName("getLowStockItems correctly identifies variants at or below product reorder level")
    void testLowStockDetection() {
        List<LowStockItem> lowStock = lowStockService.getLowStockItems();

        assertEquals(1, lowStock.size(), "Only variant with stock <= 10 should be reported");
        LowStockItem item = lowStock.get(0);

        assertEquals(lowStockVariantId, item.variantId());
        assertEquals("White 16A", item.variantName());
        assertEquals("Fabio Switch", item.productName());
        assertEquals("Havells", item.brandName());
        assertEquals("Switch", item.categoryName());
        assertEquals(Quantity.of(4), item.currentStock());
        assertEquals(Quantity.of(10), item.reorderLevel());
        assertEquals(Quantity.of(6), item.shortage(), "Shortage should be 10 - 4 = 6");
        assertEquals(Quantity.of(16), item.suggestedOrderQuantity(), "Suggested order should bring stock to 20 (2x reorder level): 20 - 4 = 16");
    }

    @Test
    @DisplayName("Filter low-stock items by Brand and Category")
    void testFilterByBrandAndCategory() {
        List<LowStockItem> havellsItems = lowStockService.getLowStockItemsByBrand("Havells");
        assertEquals(1, havellsItems.size());

        List<LowStockItem> otherBrandItems = lowStockService.getLowStockItemsByBrand("Anchor");
        assertTrue(otherBrandItems.isEmpty());

        List<LowStockItem> switchItems = lowStockService.getLowStockItemsByCategory("Switch");
        assertEquals(1, switchItems.size());

        List<LowStockItem> wireItems = lowStockService.getLowStockItemsByCategory("Wire");
        assertTrue(wireItems.isEmpty());
    }

    @Test
    @DisplayName("exportLowStockCsv generates valid CSV with correct headers and data")
    void testExportCsv() {
        String csv = lowStockService.exportLowStockCsv();

        assertNotNull(csv);
        assertTrue(csv.startsWith("Brand,Category,Product,Variant,Unit,Current Stock,Reorder Level,Shortage,Suggested Order\n"));
        assertTrue(csv.contains("Havells,Switch,Fabio Switch,White 16A,PIECE,4,10,6,16"));
    }
}
