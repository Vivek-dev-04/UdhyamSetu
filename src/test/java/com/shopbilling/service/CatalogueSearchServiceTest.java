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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CatalogueSearchService Tests (Fast Barcode Lookup & Multi-Token Search)")
class CatalogueSearchServiceTest {

    @TempDir
    Path tempDir;

    private ConnectionFactory connectionFactory;
    private TransactionHelper transactionHelper;
    private StockRepository stockRepository;
    private CatalogueSearchService searchService;
    private StockService stockService;

    private long havellsBrandId;
    private long anchorBrandId;
    private long switchesCatId;
    private long wiresCatId;

    private long variant1Id; // Havells Switch 16A White, Barcode: 8901234567890
    private long variant2Id; // Havells Switch 6A White, Barcode: 8901234567891
    private long variant3Id; // Anchor Wire 1.5 sq mm Red, Barcode: 8909876543210

    @BeforeEach
    void setUp() throws IOException {
        Path dbFile = tempDir.resolve("catalogue_search_test.db");
        DatabaseConfig config = DatabaseConfig.forFile(dbFile);
        connectionFactory = new ConnectionFactory(config);
        new FlywayMigrator(connectionFactory).migrate();

        transactionHelper = new TransactionHelper(connectionFactory);
        stockRepository = new StockRepository();
        searchService = new CatalogueSearchService(transactionHelper, stockRepository);

        SettingsRepository settingsRepository = new SettingsRepository();
        ProductVariantRepository variantRepository = new ProductVariantRepository();
        stockService = new StockService(transactionHelper, stockRepository, settingsRepository, variantRepository);

        BrandRepository brandRepository = new BrandRepository();
        CategoryRepository categoryRepository = new CategoryRepository();
        ProductRepository productRepository = new ProductRepository();
        UserRepository userRepository = new UserRepository();

        transactionHelper.executeVoid(conn -> {
            User owner = userRepository.insert(conn, new User(null, "owner", "pass", UserRole.OWNER, "Shop Owner", true, null));
            long userId = owner.id();

            Brand havells = brandRepository.insert(conn, "Havells");
            Brand anchor = brandRepository.insert(conn, "Anchor");
            havellsBrandId = havells.id();
            anchorBrandId = anchor.id();

            Category switches = categoryRepository.insert(conn, "Switch");
            Category wires = categoryRepository.insert(conn, "Wire");
            switchesCatId = switches.id();
            wiresCatId = wires.id();

            // Product 1: Havells Switch Fabio (reorder level = 10 units = 10000 scaled)
            Product p1 = productRepository.insert(conn, new Product(null, havells.id(), switches.id(), "Fabio", "PIECE", 10000L, true, null));
            ProductVariant v1 = variantRepository.insert(conn, new ProductVariant(null, p1.id(), "16A White", Money.ofRupees(150), Money.ofRupees(120), "8901234567890", true, null));
            ProductVariant v2 = variantRepository.insert(conn, new ProductVariant(null, p1.id(), "6A White", Money.ofRupees(80), Money.ofRupees(65), "8901234567891", true, null));
            variant1Id = v1.id();
            variant2Id = v2.id();

            // Product 2: Anchor Wire Roma (no reorder level)
            Product p2 = productRepository.insert(conn, new Product(null, anchor.id(), wires.id(), "Advance", "COIL", null, true, null));
            ProductVariant v3 = variantRepository.insert(conn, new ProductVariant(null, p2.id(), "1.5 Red", Money.ofRupees(1200), Money.ofRupees(950), "8909876543210", true, null));
            variant3Id = v3.id();

            // Product 3: Inactive product (should NOT be returned in search)
            Product pInactive = productRepository.insert(conn, new Product(null, havells.id(), switches.id(), "Old Discontinued", "PIECE", null, false, null));
            variantRepository.insert(conn, new ProductVariant(null, pInactive.id(), "Old 6A", Money.ofRupees(50), Money.ofRupees(40), "INACTIVE123", true, null));

            // Seed stock:
            // v1: 5 units (<= 10, so isLowStock should be true)
            stockService.recordMovementInTransaction(conn, variant1Id, StockMovementType.PURCHASE, Quantity.of(5), "PURCHASE", 1L, "Stock in", userId);
            // v2: 50 units (healthy)
            stockService.recordMovementInTransaction(conn, variant2Id, StockMovementType.PURCHASE, Quantity.of(50), "PURCHASE", 1L, "Stock in", userId);
            // v3: 15 units
            stockService.recordMovementInTransaction(conn, variant3Id, StockMovementType.PURCHASE, Quantity.of(15), "PURCHASE", 1L, "Stock in", userId);
        });
    }

    @Test
    @DisplayName("findByBarcode instantly returns matching active variant with derived stock and low-stock alert")
    void testFindByBarcode() {
        Optional<CatalogueSearchResult> resultOpt = searchService.findByBarcode("8901234567890");

        assertTrue(resultOpt.isPresent());
        CatalogueSearchResult res = resultOpt.get();
        assertEquals(variant1Id, res.variantId());
        assertEquals("Havells", res.brandName());
        assertEquals("Switch", res.categoryName());
        assertEquals("Fabio", res.productName());
        assertEquals("16A White", res.variantName());
        assertEquals("Havells Fabio 16A White", res.fullName());
        assertEquals(Money.ofRupees(150), res.mrp());
        assertEquals(Money.ofRupees(120), res.sellingPrice());
        assertEquals(Quantity.of(5), res.currentStock());
        assertTrue(res.isLowStock(), "Current stock (5) <= reorder level (10)");
        assertTrue(res.isBarcodeMatch());

        // Unknown barcode returns empty
        assertFalse(searchService.findByBarcode("UNKNOWN_999").isPresent());
    }

    @Test
    @DisplayName("searchCatalogue performs multi-token matching across brand, product, and variant")
    void testSearchCatalogueMultiToken() {
        // Query: "hav 16a" should match "Havells Fabio 16A White"
        List<CatalogueSearchResult> results = searchService.searchCatalogue("hav 16a", 10);

        assertEquals(1, results.size());
        assertEquals(variant1Id, results.get(0).variantId());

        // Query: "white" matches both 16A and 6A Havells switches
        List<CatalogueSearchResult> whiteResults = searchService.searchCatalogue("white", 10);
        assertEquals(2, whiteResults.size());

        // Inactive product variants are never returned
        List<CatalogueSearchResult> inactiveSearch = searchService.searchCatalogue("Discontinued", 10);
        assertTrue(inactiveSearch.isEmpty());
    }

    @Test
    @DisplayName("searchByCategory returns variants for selected category")
    void testSearchByCategory() {
        List<CatalogueSearchResult> switches = searchService.searchByCategory(switchesCatId, 10);
        assertEquals(2, switches.size());

        List<CatalogueSearchResult> wires = searchService.searchByCategory(wiresCatId, 10);
        assertEquals(1, wires.size());
        assertEquals("Advance", wires.get(0).productName());
    }

    @Test
    @DisplayName("searchByBrand returns variants for selected brand")
    void testSearchByBrand() {
        List<CatalogueSearchResult> havellsItems = searchService.searchByBrand(havellsBrandId, 10);
        assertEquals(2, havellsItems.size());

        List<CatalogueSearchResult> anchorItems = searchService.searchByBrand(anchorBrandId, 10);
        assertEquals(1, anchorItems.size());
    }
}
