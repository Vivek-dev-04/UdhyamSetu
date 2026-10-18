package com.shopbilling.service.pdf;

import com.shopbilling.db.BrandRepository;
import com.shopbilling.db.CategoryRepository;
import com.shopbilling.db.ConnectionFactory;
import com.shopbilling.db.DatabaseConfig;
import com.shopbilling.db.FlywayMigrator;
import com.shopbilling.db.ProductRepository;
import com.shopbilling.db.ProductVariantRepository;
import com.shopbilling.db.SettingsRepository;
import com.shopbilling.db.TransactionHelper;
import com.shopbilling.db.UserRepository;
import com.shopbilling.model.BarcodeLabelItem;
import com.shopbilling.model.BarcodeSheetConfig;
import com.shopbilling.model.Brand;
import com.shopbilling.model.Category;
import com.shopbilling.model.Money;
import com.shopbilling.model.Product;
import com.shopbilling.model.ProductVariant;
import com.shopbilling.model.User;
import com.shopbilling.model.UserRole;
import com.shopbilling.service.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("BarcodeLabelService Tests (Barcode Generation & Printable Sticker Sheets)")
class BarcodeLabelServiceTest {

    @TempDir
    Path tempDir;

    private ConnectionFactory connectionFactory;
    private TransactionHelper transactionHelper;
    private ProductVariantRepository variantRepository;
    private ProductRepository productRepository;
    private BrandRepository brandRepository;
    private SettingsRepository settingsRepository;

    private BarcodeLabelService barcodeLabelService;

    private long variantWithoutBarcodeId;
    private long variantWithBarcodeId;

    @BeforeEach
    void setUp() throws IOException {
        Path dbFile = tempDir.resolve("barcode_label_test.db");
        DatabaseConfig config = DatabaseConfig.forFile(dbFile);
        connectionFactory = new ConnectionFactory(config);
        new FlywayMigrator(connectionFactory).migrate();

        transactionHelper = new TransactionHelper(connectionFactory);
        variantRepository = new ProductVariantRepository();
        productRepository = new ProductRepository();
        brandRepository = new BrandRepository();
        settingsRepository = new SettingsRepository();

        barcodeLabelService = new BarcodeLabelService(
                transactionHelper,
                variantRepository,
                productRepository,
                brandRepository,
                settingsRepository
        );

        CategoryRepository categoryRepository = new CategoryRepository();
        UserRepository userRepository = new UserRepository();

        transactionHelper.executeVoid(conn -> {
            User owner = userRepository.insert(conn, new User(null, "owner", "pass", UserRole.OWNER, "Shop Owner", true, null));
            settingsRepository.setSetting(conn, "SHOP_NAME", "RAMA ELECTRICALS", owner.id());

            Brand havells = brandRepository.insert(conn, "Havells");
            Category fanCat = categoryRepository.insert(conn, "Fans");

            Product fan = productRepository.insert(conn, new Product(
                    null, havells.id(), fanCat.id(), "Ceiling Fan 1200mm", "PIECE", 5L, true, null
            ));

            ProductVariant v1 = variantRepository.insert(conn, new ProductVariant(
                    null, fan.id(), "Bianco White", Money.ofRupees(2400), Money.ofRupees(2150), null, true, null
            ));
            variantWithoutBarcodeId = v1.id();

            ProductVariant v2 = variantRepository.insert(conn, new ProductVariant(
                    null, fan.id(), "Espresso Brown", Money.ofRupees(2400), Money.ofRupees(2150), "HVL-FAN-BRN-101", true, null
            ));
            variantWithBarcodeId = v2.id();
        });
    }

    @Test
    @DisplayName("generateBarcodeIfMissing auto-assigns 890... barcode when none exists")
    void testAutoAssignBarcode() {
        ProductVariant updated = barcodeLabelService.generateBarcodeIfMissing(variantWithoutBarcodeId);
        assertNotNull(updated.barcode());
        assertTrue(updated.barcode().startsWith("890"));
        assertEquals(String.format("890%09d", variantWithoutBarcodeId), updated.barcode());
    }

    @Test
    @DisplayName("generateBarcodeIfMissing preserves existing barcode")
    void testPreserveExistingBarcode() {
        ProductVariant existing = barcodeLabelService.generateBarcodeIfMissing(variantWithBarcodeId);
        assertEquals("HVL-FAN-BRN-101", existing.barcode());
    }

    @Test
    @DisplayName("getLabelItemForVariant correctly bundles variant, product, brand, and pricing")
    void testGetLabelItem() {
        BarcodeLabelItem item = barcodeLabelService.getLabelItemForVariant(variantWithBarcodeId, 5);

        assertEquals("Ceiling Fan 1200mm", item.productName());
        assertEquals("Havells", item.brandName());
        assertEquals("Espresso Brown", item.variantName());
        assertEquals("HVL-FAN-BRN-101", item.barcode());
        assertEquals(Money.ofRupees(2400), item.mrp());
        assertEquals(Money.ofRupees(2150), item.sellingPrice());
        assertEquals(5, item.printCount());
    }

    @Test
    @DisplayName("renderBarcodeSheetPdf generates valid A4 PDF sheet with 24-up layout")
    void testRenderBarcodeSheetPdf() {
        BarcodeLabelItem item1 = barcodeLabelService.getLabelItemForVariant(variantWithoutBarcodeId, 12);
        BarcodeLabelItem item2 = barcodeLabelService.getLabelItemForVariant(variantWithBarcodeId, 12);

        byte[] pdfBytes = barcodeLabelService.renderBarcodeSheetPdf(List.of(item1, item2), BarcodeSheetConfig.DEFAULT_24_UP);

        assertNotNull(pdfBytes);
        assertTrue(pdfBytes.length > 1000, "PDF should have substantial content (bytes=" + pdfBytes.length + ")");
        assertEquals("%PDF-", new String(pdfBytes, 0, 5));
    }

    @Test
    @DisplayName("writeBarcodeSheetPdf streams valid PDF with multi-page overflow and custom 40-up config")
    void testWriteMultiPageBarcodeSheetPdf() {
        BarcodeLabelItem item = barcodeLabelService.getLabelItemForVariant(variantWithBarcodeId, 50);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        barcodeLabelService.writeBarcodeSheetPdf(List.of(item), BarcodeSheetConfig.COMPACT_40_UP, baos);

        byte[] output = baos.toByteArray();
        assertNotNull(output);
        assertTrue(output.length > 2000);
        assertEquals("%PDF-", new String(output, 0, 5));
    }

    @Test
    @DisplayName("Empty label list renders empty message PDF gracefully")
    void testEmptyLabelList() {
        byte[] pdf = barcodeLabelService.renderBarcodeSheetPdf(List.of(), BarcodeSheetConfig.DEFAULT_24_UP);
        assertNotNull(pdf);
        assertTrue(pdf.length > 200);
        assertEquals("%PDF-", new String(pdf, 0, 5));
    }

    @Test
    @DisplayName("Non-existent variantId throws EntityNotFoundException")
    void testNonExistentVariantThrows() {
        assertThrows(EntityNotFoundException.class, () -> barcodeLabelService.generateBarcodeIfMissing(9999L));
        assertThrows(EntityNotFoundException.class, () -> barcodeLabelService.getLabelItemForVariant(9999L, 1));
    }
}
