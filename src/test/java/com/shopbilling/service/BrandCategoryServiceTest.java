package com.shopbilling.service;

import com.shopbilling.db.BrandRepository;
import com.shopbilling.db.CategoryRepository;
import com.shopbilling.db.ConnectionFactory;
import com.shopbilling.db.DatabaseConfig;
import com.shopbilling.db.FlywayMigrator;
import com.shopbilling.db.TransactionHelper;
import com.shopbilling.model.Brand;
import com.shopbilling.model.Category;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrandCategoryServiceTest {

    private BrandCategoryService service;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        Path dbFile = tempDir.resolve("brand_category_test.db");
        DatabaseConfig config = DatabaseConfig.forFile(dbFile);
        ConnectionFactory connectionFactory = new ConnectionFactory(config);

        // Run migrations
        FlywayMigrator migrator = new FlywayMigrator(connectionFactory);
        migrator.migrate();

        TransactionHelper transactionHelper = new TransactionHelper(connectionFactory);
        BrandRepository brandRepository = new BrandRepository();
        CategoryRepository categoryRepository = new CategoryRepository();

        service = new BrandCategoryService(transactionHelper, brandRepository, categoryRepository);
    }

    @Test
    @DisplayName("Should create, retrieve, and list brands alphabetically")
    void shouldCreateAndListBrands() {
        Brand vguard = service.createBrand("V-Guard");
        Brand havells = service.createBrand("Havells");
        Brand anchor = service.createBrand("Anchor");

        assertNotNull(vguard.id());
        assertEquals("V-Guard", vguard.name());

        Optional<Brand> found = service.getBrandById(havells.id());
        assertTrue(found.isPresent());
        assertEquals("Havells", found.get().name());

        List<Brand> all = service.getAllBrands();
        assertEquals(3, all.size());
        assertEquals("Anchor", all.get(0).name());
        assertEquals("Havells", all.get(1).name());
        assertEquals("V-Guard", all.get(2).name());
    }

    @Test
    @DisplayName("Should prevent duplicate brand names case-insensitively")
    void shouldPreventDuplicateBrands() {
        service.createBrand("Havells");

        assertThrows(DuplicateEntityException.class, () ->
                service.createBrand("havells")
        );
        assertThrows(DuplicateEntityException.class, () ->
                service.createBrand("  HAVELLS  ")
        );
    }

    @Test
    @DisplayName("Should validate brand name on creation")
    void shouldValidateBrandName() {
        assertThrows(ValidationException.class, () -> service.createBrand(null));
        assertThrows(ValidationException.class, () -> service.createBrand(""));
        assertThrows(ValidationException.class, () -> service.createBrand("   "));
    }

    @Test
    @DisplayName("Should update brand name and prevent conflicting duplicates")
    void shouldUpdateBrand() {
        Brand b1 = service.createBrand("Anchor");
        Brand b2 = service.createBrand("Veto");

        Brand updated = service.updateBrand(b1.id(), "Anchor by Panasonic");
        assertEquals("Anchor by Panasonic", updated.name());

        // Updating with own name should succeed
        service.updateBrand(b1.id(), "Anchor by Panasonic");

        // Updating to an existing other brand name should fail
        assertThrows(DuplicateEntityException.class, () ->
                service.updateBrand(b1.id(), "Veto")
        );

        // Updating non-existent brand
        assertThrows(EntityNotFoundException.class, () ->
                service.updateBrand(9999L, "NonExistent")
        );
    }

    @Test
    @DisplayName("Should create, retrieve, and list categories")
    void shouldCreateAndListCategories() {
        Category switchCat = service.createCategory("Switch");
        Category wireCat = service.createCategory("Wire");
        Category lightCat = service.createCategory("Light");

        assertNotNull(switchCat.id());
        assertEquals("Switch", switchCat.name());

        List<Category> all = service.getAllCategories();
        assertEquals(3, all.size());
        assertEquals("Light", all.get(0).name());
        assertEquals("Switch", all.get(1).name());
        assertEquals("Wire", all.get(2).name());
    }

    @Test
    @DisplayName("Should prevent duplicate category names case-insensitively")
    void shouldPreventDuplicateCategories() {
        service.createCategory("Socket");

        assertThrows(DuplicateEntityException.class, () ->
                service.createCategory("socket")
        );
        assertThrows(DuplicateEntityException.class, () ->
                service.createCategory("  SOCKET  ")
        );
    }

    @Test
    @DisplayName("Should update category name and validate uniqueness")
    void shouldUpdateCategory() {
        Category c1 = service.createCategory("Switch");
        Category c2 = service.createCategory("Socket");

        Category updated = service.updateCategory(c1.id(), "Modular Switch");
        assertEquals("Modular Switch", updated.name());

        assertThrows(DuplicateEntityException.class, () ->
                service.updateCategory(c1.id(), "Socket")
        );
        assertThrows(EntityNotFoundException.class, () ->
                service.updateCategory(8888L, "Ghost Category")
        );
    }
}
