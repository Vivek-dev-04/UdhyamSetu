package com.shopbilling.service;

import com.shopbilling.db.BrandRepository;
import com.shopbilling.db.CategoryRepository;
import com.shopbilling.db.ConnectionFactory;
import com.shopbilling.db.DatabaseConfig;
import com.shopbilling.db.FlywayMigrator;
import com.shopbilling.db.ProductRepository;
import com.shopbilling.db.ProductVariantRepository;
import com.shopbilling.db.TransactionHelper;
import com.shopbilling.db.UnitRepository;
import com.shopbilling.model.Brand;
import com.shopbilling.model.Category;
import com.shopbilling.model.Money;
import com.shopbilling.model.Product;
import com.shopbilling.model.ProductVariant;
import com.shopbilling.model.ProductWithVariants;
import com.shopbilling.model.Quantity;
import com.shopbilling.model.Unit;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductServiceTest {

    private ProductService productService;
    private BrandCategoryService brandCategoryService;

    private Brand brandHavells;
    private Category catSwitch;
    private Category catWire;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        Path dbFile = tempDir.resolve("product_service_test.db");
        DatabaseConfig config = DatabaseConfig.forFile(dbFile);
        ConnectionFactory connectionFactory = new ConnectionFactory(config);

        // Run migrations
        FlywayMigrator migrator = new FlywayMigrator(connectionFactory);
        migrator.migrate();

        TransactionHelper transactionHelper = new TransactionHelper(connectionFactory);
        BrandRepository brandRepo = new BrandRepository();
        CategoryRepository catRepo = new CategoryRepository();
        UnitRepository unitRepo = new UnitRepository();
        ProductRepository prodRepo = new ProductRepository();
        ProductVariantRepository variantRepo = new ProductVariantRepository();

        brandCategoryService = new BrandCategoryService(transactionHelper, brandRepo, catRepo);
        productService = new ProductService(transactionHelper, prodRepo, variantRepo, brandRepo, catRepo, unitRepo);

        // Seed brand and categories
        brandHavells = brandCategoryService.createBrand("Havells");
        catSwitch = brandCategoryService.createCategory("Switch");
        catWire = brandCategoryService.createCategory("Wire");
    }

    @Test
    @DisplayName("Should create product with piece unit and integer reorder level")
    void shouldCreateProductWithPieceUnit() {
        Product p = productService.createProduct(
                brandHavells.id(),
                catSwitch.id(),
                "Fabio 16A 1-Way Switch",
                "PIECE",
                Quantity.of(50)
        );

        assertNotNull(p.id());
        assertEquals("Fabio 16A 1-Way Switch", p.name());
        assertEquals("PIECE", p.unitCode());
        assertEquals(50000L, p.reorderLevelScaled());
        assertTrue(p.isActive());
    }

    @Test
    @DisplayName("Should create product with metre unit and decimal reorder level")
    void shouldCreateProductWithMetreUnit() {
        Product wire = productService.createProduct(
                brandHavells.id(),
                catWire.id(),
                "LifeLine Plus 1.5 sq mm",
                "METRE",
                Quantity.parse("15.5")
        );

        assertEquals("METRE", wire.unitCode());
        assertEquals(15500L, wire.reorderLevelScaled());
    }

    @Test
    @DisplayName("Should reject decimal reorder level on units that do not allow decimals")
    void shouldRejectDecimalOnPieceUnit() {
        assertThrows(ValidationException.class, () ->
                productService.createProduct(
                        brandHavells.id(),
                        catSwitch.id(),
                        "Invalid Reorder Switch",
                        "PIECE",
                        Quantity.parse("10.5") // Fractional quantity not allowed for PIECE!
                )
        );
    }

    @Test
    @DisplayName("Should prevent duplicate product under same brand and category")
    void shouldPreventDuplicateProduct() {
        productService.createProduct(
                brandHavells.id(),
                catSwitch.id(),
                "Fabio 16A",
                "PIECE",
                null
        );

        assertThrows(DuplicateEntityException.class, () ->
                productService.createProduct(
                        brandHavells.id(),
                        catSwitch.id(),
                        "Fabio 16A",
                        "PIECE",
                        null
                )
        );
    }

    @Test
    @DisplayName("Should add variants with valid MRP, selling price, and barcode")
    void shouldAddVariants() {
        Product switchProd = productService.createProduct(
                brandHavells.id(),
                catSwitch.id(),
                "Fabio Modular Switch",
                "PIECE",
                null
        );

        ProductVariant v1 = productService.addVariant(
                switchProd.id(),
                "10A White",
                Money.ofRupees(120),
                Money.ofRupees(100),
                "890123456001"
        );

        ProductVariant v2 = productService.addVariant(
                switchProd.id(),
                "16A White",
                Money.ofRupees(160),
                Money.ofRupees(140),
                "890123456002"
        );

        assertNotNull(v1.id());
        assertEquals("10A White", v1.variantName());
        assertEquals(Money.ofRupees(120), v1.mrp());
        assertEquals(Money.ofRupees(100), v1.sellingPrice());
        assertEquals("890123456001", v1.barcode());

        // Fast barcode lookup
        Optional<ProductVariant> foundByBarcode = productService.findVariantByBarcode("890123456002");
        assertTrue(foundByBarcode.isPresent());
        assertEquals("16A White", foundByBarcode.get().variantName());
    }

    @Test
    @DisplayName("Should reject variant if selling price exceeds MRP")
    void shouldRejectSellingPriceExceedingMrp() {
        Product prod = productService.createProduct(
                brandHavells.id(),
                catSwitch.id(),
                "Test Switch",
                "PIECE",
                null
        );

        assertThrows(ValidationException.class, () ->
                productService.addVariant(
                        prod.id(),
                        "Standard",
                        Money.ofRupees(100), // MRP
                        Money.ofRupees(110), // Selling price > MRP -> Illegal!
                        null
                )
        );
    }

    @Test
    @DisplayName("Should reject duplicate barcode across variants")
    void shouldRejectDuplicateBarcode() {
        Product prod1 = productService.createProduct(brandHavells.id(), catSwitch.id(), "Prod 1", "PIECE", null);
        Product prod2 = productService.createProduct(brandHavells.id(), catWire.id(), "Prod 2", "METRE", null);

        productService.addVariant(prod1.id(), "Var A", Money.ofRupees(100), Money.ofRupees(90), "BARCODE999");

        assertThrows(DuplicateEntityException.class, () ->
                productService.addVariant(prod2.id(), "Var B", Money.ofRupees(200), Money.ofRupees(180), "BARCODE999")
        );
    }

    @Test
    @DisplayName("Should search products and return composite ProductWithVariants")
    void shouldSearchProductsWithVariants() {
        Product prod = productService.createProduct(brandHavells.id(), catSwitch.id(), "Fabio Switch", "PIECE", null);
        productService.addVariant(prod.id(), "6A", Money.ofRupees(80), Money.ofRupees(70), null);
        productService.addVariant(prod.id(), "16A", Money.ofRupees(150), Money.ofRupees(130), null);

        List<ProductWithVariants> results = productService.searchProducts("Fabio");
        assertEquals(1, results.size());

        ProductWithVariants pwv = results.get(0);
        assertEquals("Fabio Switch", pwv.product().name());
        assertEquals("Havells", pwv.brand().name());
        assertEquals("Switch", pwv.category().name());
        assertEquals("PIECE", pwv.unit().code());
        assertEquals(2, pwv.variants().size());
    }

    @Test
    @DisplayName("Should list all seeded units")
    void shouldListAllUnits() {
        List<Unit> units = productService.getAllUnits();
        assertFalse(units.isEmpty());
        assertTrue(units.stream().anyMatch(u -> u.code().equals("PIECE") && !u.allowsDecimal()));
        assertTrue(units.stream().anyMatch(u -> u.code().equals("METRE") && u.allowsDecimal()));
    }
}
