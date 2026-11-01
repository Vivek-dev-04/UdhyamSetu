package com.shopbilling.service;

import com.shopbilling.db.*;
import com.shopbilling.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class HsnServiceTest {

    private HsnService hsnService;
    private ProductService productService;
    private TransactionHelper transactionHelper;
    private BrandRepository brandRepo;
    private CategoryRepository catRepo;
    private UnitRepository unitRepo;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        Path dbFile = tempDir.resolve("hsn_service_test.db");
        DatabaseConfig config = DatabaseConfig.forFile(dbFile);
        ConnectionFactory connectionFactory = new ConnectionFactory(config);

        // Run Flyway migrations including V4
        FlywayMigrator migrator = new FlywayMigrator(connectionFactory);
        migrator.migrate();

        transactionHelper = new TransactionHelper(connectionFactory);
        HsnRepository hsnRepo = new HsnRepository();
        ProductRepository prodRepo = new ProductRepository();
        ProductVariantRepository variantRepo = new ProductVariantRepository();
        brandRepo = new BrandRepository();
        catRepo = new CategoryRepository();
        unitRepo = new UnitRepository();

        hsnService = new HsnService(transactionHelper, hsnRepo);
        productService = new ProductService(transactionHelper, prodRepo, variantRepo, brandRepo, catRepo, unitRepo);
    }

    @Test
    @DisplayName("Search prioritization: Exact code match ranks before partial code match and description match")
    void testSearchPrioritization() {
        // Master directory has seeded: 8544 (Insulated wire...), 85446020 (Plastic insulated electrical wire...)
        // Search query "8544"
        List<HsnItem> results = hsnService.searchHsn("8544", 10);
        assertFalse(results.isEmpty(), "Results should not be empty");
        assertEquals("8544", results.get(0).hsnCode(), "Exact code match must be ranked #1");
        assertTrue(results.stream().anyMatch(h -> "85446020".equals(h.hsnCode())), "Prefix code match must be present");
    }

    @Test
    @DisplayName("Search by product description case-insensitive")
    void testSearchByDescription() {
        List<HsnItem> results = hsnService.searchHsn("switch", 10);
        assertFalse(results.isEmpty(), "Should find electrical switch classifications");
        assertTrue(results.stream().anyMatch(h -> h.description().toLowerCase().contains("switch")));

        // Case-insensitive uppercase search
        List<HsnItem> upperResults = hsnService.searchHsn("SWITCH", 10);
        assertEquals(results.size(), upperResults.size());
    }

    @Test
    @DisplayName("Search partial HSN code")
    void testSearchPartialCode() {
        List<HsnItem> results = hsnService.searchHsn("8536", 10);
        assertFalse(results.isEmpty());
        assertEquals("8536", results.get(0).hsnCode());
        assertTrue(results.stream().anyMatch(h -> h.hsnCode().startsWith("8536")));
    }

    @Test
    @DisplayName("Create new HSN classification and prevent duplicates")
    void testCreateHsnAndDuplicateValidation() {
        HsnItem item = hsnService.createHsn("998313", "Information technology consulting services", "SERVICES");
        assertNotNull(item.id());
        assertEquals("998313", item.hsnCode());
        assertEquals("SERVICES", item.productType());

        // Attempt duplicate
        assertThrows(DuplicateEntityException.class, () ->
                hsnService.createHsn("998313", "Duplicate entry", "SERVICES")
        );
    }

    @Test
    @DisplayName("Update existing HSN classification")
    void testUpdateHsn() {
        HsnItem created = hsnService.createHsn("8501", "Electric motors and generators", "GOODS");
        HsnItem updated = hsnService.updateHsn(created.id(), "8501", "Updated electric motors, generators and sets", "GOODS");
        assertEquals("Updated electric motors, generators and sets", updated.description());

        Optional<HsnItem> reloaded = hsnService.getHsnById(created.id());
        assertTrue(reloaded.isPresent());
        assertEquals("Updated electric motors, generators and sets", reloaded.get().description());
    }

    @Test
    @DisplayName("Delete in-use HSN is strictly blocked to preserve catalogue and tax audit integrity")
    void testDeleteInUseBlocked() {
        // 8544 is seeded. Let us assign 8544 to a product
        Brand b = transactionHelper.execute(conn -> brandRepo.insert(conn, "Polycab"));
        Category c = transactionHelper.execute(conn -> catRepo.insert(conn, "Cables"));

        Product prod = productService.createProduct(b.id(), c.id(), "Polycab 2.5sqmm Wire", "COIL", null, "8544", 18.0);
        assertNotNull(prod);

        HsnItem hsn8544 = hsnService.getHsnByCode("8544").orElseThrow();
        ValidationException ex = assertThrows(ValidationException.class, () ->
                hsnService.deleteHsn(hsn8544.id())
        );
        assertTrue(ex.getMessage().contains("assigned to products"));
    }

    @Test
    @DisplayName("Delete unused HSN succeeds")
    void testDeleteUnused() {
        HsnItem item = hsnService.createHsn("123456", "Temporary Unused Product", "GOODS");
        assertNotNull(item.id());

        boolean deleted = hsnService.deleteHsn(item.id());
        assertTrue(deleted);
        assertTrue(hsnService.getHsnByCode("123456").isEmpty());
    }
}
