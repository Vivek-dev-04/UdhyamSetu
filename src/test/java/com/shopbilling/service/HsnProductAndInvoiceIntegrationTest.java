package com.shopbilling.service;

import com.shopbilling.db.*;
import com.shopbilling.model.*;
import com.shopbilling.service.pdf.BillPdfService;
import com.shopbilling.service.pdf.BillPrintModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HsnProductAndInvoiceIntegrationTest {

    private TransactionHelper transactionHelper;
    private ProductService productService;
    private GenerateBillService generateBillService;
    private SaleRepository saleRepository;
    private StockService stockService;
    private BillPdfService billPdfService;

    private Brand brandPolycab;
    private Category catCables;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws Exception {
        Path dbPath = tempDir.resolve("hsn_integration_test.db");
        DatabaseConfig config = DatabaseConfig.forFile(dbPath);
        ConnectionFactory connectionFactory = new ConnectionFactory(config);
        new FlywayMigrator(connectionFactory).migrate();

        transactionHelper = new TransactionHelper(connectionFactory);
        saleRepository = new SaleRepository();
        PaymentRepository paymentRepository = new PaymentRepository();
        CustomerRepository customerRepository = new CustomerRepository();
        CustomerLedgerRepository customerLedgerRepository = new CustomerLedgerRepository();
        ProductVariantRepository variantRepository = new ProductVariantRepository();
        ProductRepository productRepository = new ProductRepository();
        UnitRepository unitRepository = new UnitRepository();
        SettingsRepository settingsRepository = new SettingsRepository();
        StockRepository stockRepository = new StockRepository();
        BillCounterRepository billCounterRepository = new BillCounterRepository();
        BillCounterService billCounterService = new BillCounterService(transactionHelper, billCounterRepository);
        SupplierRepository supplierRepo = new SupplierRepository();
        BrandRepository brandRepo = new BrandRepository();
        CategoryRepository catRepo = new CategoryRepository();

        BrandCategoryService brandCategoryService = new BrandCategoryService(transactionHelper, brandRepo, catRepo);
        productService = new ProductService(transactionHelper, productRepository, variantRepository, brandRepo, catRepo, unitRepository);
        stockService = new StockService(transactionHelper, stockRepository, settingsRepository, variantRepository);
        CustomerSupplierService customerSupplierService = new CustomerSupplierService(transactionHelper, customerRepository, supplierRepo);
        PaymentValidationService paymentValidationService = new PaymentValidationService(transactionHelper, customerRepository);
        IstClock clock = IstClock.system();

        generateBillService = new GenerateBillService(
                transactionHelper,
                saleRepository,
                billCounterService,
                paymentRepository,
                paymentValidationService,
                stockService,
                customerLedgerRepository,
                customerRepository,
                variantRepository,
                productRepository,
                unitRepository,
                settingsRepository,
                clock
        );

        billPdfService = new BillPdfService(
                transactionHelper,
                saleRepository,
                paymentRepository,
                customerRepository,
                variantRepository,
                productRepository,
                brandRepo,
                unitRepository,
                settingsRepository
        );

        // Seed Users
        try (Connection conn = connectionFactory.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO users (id, username, password_hash, role, full_name, is_active) " +
                    "VALUES (1, 'owner', '$2a$10$xyz', 'OWNER', 'Store Owner', 1);");
        }

        // Setup Brand & Category
        brandPolycab = brandCategoryService.createBrand("Polycab");
        catCables = brandCategoryService.createCategory("Wires & Cables");
    }

    @Test
    @DisplayName("Product HSN creation, invoice line item snapshot, and historical invoice immutability")
    void testProductHsnAndInvoiceSnapshotImmutability() {
        // 1. Create product with initial HSN "85446020" and GST 18.0%
        Product cableProduct = productService.createProduct(
                brandPolycab.id(),
                catCables.id(),
                "Polycab 4.0 sqmm Heavy Duty Industrial Cable",
                "METRE",
                Quantity.of(100),
                "85446020",
                18.0
        );
        assertEquals("85446020", cableProduct.hsnCode());
        assertEquals(18.0, cableProduct.gstRate());

        ProductVariant variant = productService.addVariant(
                cableProduct.id(),
                "100M Drum",
                Money.ofRupees(5000),
                Money.ofRupees(4500),
                null
        );

        // Record stock-in of 500 Metres so stock deduction passes
        stockService.recordMovement(
                variant.id(),
                StockMovementType.PURCHASE,
                Quantity.of(500),
                "PURCHASE",
                1L,
                "Initial stock in",
                1L
        );

        // 2. Generate Invoice #1 (Billed Sale)
        List<SaleItemInput> items1 = List.of(new SaleItemInput(
                variant.id(),
                Quantity.of(50),
                Money.ofRupees(4500),
                Money.ZERO
        ));
        PaymentSplit payments1 = PaymentSplit.single(PaymentMode.CASH, Money.ofRupees(225000));

        Sale invoice1 = generateBillService.generateBill(
                null,
                LocalDate.of(2026, 4, 10),
                items1,
                Money.ZERO,
                payments1,
                1L
        );

        assertNotNull(invoice1.id());
        assertEquals("BILL", invoice1.saleMode().name());
        assertEquals(1, invoice1.items().size());

        SaleItem lineItem1 = invoice1.items().get(0);
        assertEquals("85446020", lineItem1.hsnCode(), "Invoice line item must snapshot product HSN");
        assertEquals(18.0, lineItem1.gstRate(), "Invoice line item must snapshot product GST rate");

        // Verify PDF print model reflects snapshot
        BillPrintModel printModel1 = billPdfService.loadPrintModel(invoice1.id());
        assertEquals("85446020", printModel1.items().get(0).hsnSac());
        assertEquals(18.0, printModel1.items().get(0).gstRatePercent());

        // 3. Update the product to a different classification "85365020" and GST 12.0%
        Product updatedCableProduct = productService.updateProduct(
                cableProduct.id(),
                brandPolycab.id(),
                catCables.id(),
                "Polycab 4.0 sqmm Heavy Duty Industrial Cable",
                "METRE",
                Quantity.of(100),
                true,
                "85365020",
                12.0
        );
        assertEquals("85365020", updatedCableProduct.hsnCode());
        assertEquals(12.0, updatedCableProduct.gstRate());

        // 4. Generate Invoice #2 with the updated product
        List<SaleItemInput> items2 = List.of(new SaleItemInput(
                variant.id(),
                Quantity.of(20),
                Money.ofRupees(4500),
                Money.ZERO
        ));
        PaymentSplit payments2 = PaymentSplit.single(PaymentMode.CASH, Money.ofRupees(90000));

        Sale invoice2 = generateBillService.generateBill(
                null,
                LocalDate.of(2026, 4, 15),
                items2,
                Money.ZERO,
                payments2,
                1L
        );

        assertEquals("85365020", invoice2.items().get(0).hsnCode(), "Invoice #2 must snapshot new HSN");
        assertEquals(12.0, invoice2.items().get(0).gstRate(), "Invoice #2 must snapshot new GST rate");

        // 5. CRITICAL REQUIREMENT (Section 8 & 11): Verify Historical Invoice #1 is completely UNCHANGED!
        List<SaleItem> historicalItems1 = transactionHelper.executeRead(conn ->
                saleRepository.findItemsBySaleId(conn, invoice1.id())
        );
        assertEquals(1, historicalItems1.size());
        assertEquals("85446020", historicalItems1.get(0).hsnCode(),
                "Historical Invoice line item MUST remain 85446020 even after product was updated to 85365020");
        assertEquals(18.0, historicalItems1.get(0).gstRate(),
                "Historical Invoice line item GST rate MUST remain 18.0 even after product was updated to 12.0");

        // Verify PDF rendering generates valid output stream without errors for both historical and new bills
        ByteArrayOutputStream pdfStream1 = new ByteArrayOutputStream();
        billPdfService.renderPdf(printModel1, pdfStream1);
        assertTrue(pdfStream1.size() > 1000, "Historical PDF should render successfully with content");

        BillPrintModel printModel2 = billPdfService.loadPrintModel(invoice2.id());
        ByteArrayOutputStream pdfStream2 = new ByteArrayOutputStream();
        billPdfService.renderPdf(printModel2, pdfStream2);
        assertTrue(pdfStream2.size() > 1000, "New PDF should render successfully with content");
    }
}
