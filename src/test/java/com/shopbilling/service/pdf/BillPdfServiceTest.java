package com.shopbilling.service.pdf;

import com.shopbilling.db.BillCounterRepository;
import com.shopbilling.db.BrandRepository;
import com.shopbilling.db.CategoryRepository;
import com.shopbilling.db.ConnectionFactory;
import com.shopbilling.db.CustomerLedgerRepository;
import com.shopbilling.db.CustomerRepository;
import com.shopbilling.db.DatabaseConfig;
import com.shopbilling.db.FlywayMigrator;
import com.shopbilling.db.PaymentRepository;
import com.shopbilling.db.ProductRepository;
import com.shopbilling.db.ProductVariantRepository;
import com.shopbilling.db.SaleRepository;
import com.shopbilling.db.SettingsRepository;
import com.shopbilling.db.StockRepository;
import com.shopbilling.db.SupplierRepository;
import com.shopbilling.db.TransactionHelper;
import com.shopbilling.db.UnitRepository;
import com.shopbilling.model.Brand;
import com.shopbilling.model.Category;
import com.shopbilling.model.Customer;
import com.shopbilling.model.CustomerType;
import com.shopbilling.model.Money;
import com.shopbilling.model.PaymentInput;
import com.shopbilling.model.PaymentMode;
import com.shopbilling.model.PaymentSplit;
import com.shopbilling.model.Product;
import com.shopbilling.model.ProductVariant;
import com.shopbilling.model.Quantity;
import com.shopbilling.model.Sale;
import com.shopbilling.model.SaleItemInput;
import com.shopbilling.model.StockMovementType;
import com.shopbilling.service.BillCounterService;
import com.shopbilling.service.BrandCategoryService;
import com.shopbilling.service.CustomerSupplierService;
import com.shopbilling.service.GenerateBillService;
import com.shopbilling.service.IstClock;
import com.shopbilling.service.PaymentValidationService;
import com.shopbilling.service.ProductService;
import com.shopbilling.service.StockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BillPdfServiceTest {

    private ConnectionFactory connectionFactory;
    private TransactionHelper transactionHelper;
    private SaleRepository saleRepository;
    private PaymentRepository paymentRepository;
    private CustomerRepository customerRepository;
    private ProductVariantRepository variantRepository;
    private ProductRepository productRepository;
    private BrandRepository brandRepository;
    private UnitRepository unitRepository;
    private SettingsRepository settingsRepository;
    private GenerateBillService generateBillService;
    private BillPdfService billPdfService;

    private Customer technicianCustomer;
    private ProductVariant switchVariant;
    private ProductVariant wireVariant;

    private static final long OWNER_USER_ID = 1L;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws Exception {
        Path dbPath = tempDir.resolve("bill_pdf_test.db");
        DatabaseConfig config = DatabaseConfig.forFile(dbPath);
        connectionFactory = new ConnectionFactory(config);
        new FlywayMigrator(connectionFactory).migrate();

        transactionHelper = new TransactionHelper(connectionFactory);
        saleRepository = new SaleRepository();
        paymentRepository = new PaymentRepository();
        customerRepository = new CustomerRepository();
        CustomerLedgerRepository customerLedgerRepository = new CustomerLedgerRepository();
        variantRepository = new ProductVariantRepository();
        productRepository = new ProductRepository();
        unitRepository = new UnitRepository();
        settingsRepository = new SettingsRepository();
        StockRepository stockRepository = new StockRepository();
        BillCounterRepository billCounterRepository = new BillCounterRepository();
        BillCounterService billCounterService = new BillCounterService(transactionHelper, billCounterRepository);
        SupplierRepository supplierRepo = new SupplierRepository();
        brandRepository = new BrandRepository();
        CategoryRepository catRepo = new CategoryRepository();

        BrandCategoryService brandCategoryService = new BrandCategoryService(transactionHelper, brandRepository, catRepo);
        ProductService productService = new ProductService(transactionHelper, productRepository, variantRepository, brandRepository, catRepo, unitRepository);
        StockService stockService = new StockService(transactionHelper, stockRepository, settingsRepository, variantRepository);
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
                brandRepository,
                unitRepository,
                settingsRepository
        );

        // Seed owner user
        try (Connection conn = connectionFactory.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO users (id, username, password_hash, role, full_name) " +
                    "VALUES (1, 'owner', 'hash1', 'OWNER', 'Shop Owner');");
        }

        Brand havells = brandCategoryService.createBrand("Havells");
        Category switches = brandCategoryService.createCategory("Switch");
        Category wires = brandCategoryService.createCategory("Wire");

        Product p1 = productService.createProduct(havells.id(), switches.id(), "Fabio 16A Switch", "PIECE", null);
        switchVariant = productService.addVariant(p1.id(), "White", Money.ofRupees(150), Money.ofRupees(120), "BAR123");

        Product p2 = productService.createProduct(havells.id(), wires.id(), "LifeLine Plus Wire", "METRE", null);
        wireVariant = productService.addVariant(p2.id(), "Red", Money.ofRupees(40), Money.ofRupees(35), "BAR456");

        technicianCustomer = customerSupplierService.createCustomer("Ravi Electricals", "9876543210", CustomerType.TECHNICIAN, "Civil Lines", Money.ofRupees(10000), "Contractor");

        stockService.recordMovement(switchVariant.id(), StockMovementType.PURCHASE,
                Quantity.of(100), "PURCHASE", 1, "Opening Stock", OWNER_USER_ID);
        stockService.recordMovement(wireVariant.id(), StockMovementType.PURCHASE,
                Quantity.of(500), "PURCHASE", 1, "Opening Stock", OWNER_USER_ID);
    }

    @Test
    @DisplayName("IndianNumberToWords: converts amounts correctly")
    void testIndianNumberToWords() {
        assertEquals("Zero Rupees Only", IndianNumberToWords.convertToWords(0));
        assertEquals("Fifty Rupees Only", IndianNumberToWords.convertToWords(50));
        assertEquals("One Hundred Twenty Rupees Only", IndianNumberToWords.convertToWords(120));
        assertEquals("One Thousand Two Hundred Fifty Rupees Only", IndianNumberToWords.convertToWords(1250));
        assertEquals("Fifteen Thousand Four Hundred Rupees Only", IndianNumberToWords.convertToWords(15400));
        assertEquals("One Lakh Fifty Thousand Rupees Only", IndianNumberToWords.convertToWords(150000));
        assertEquals("One Crore Five Lakh Rupees Only", IndianNumberToWords.convertToWords(10500000));
    }

    @Test
    @DisplayName("Generate PDF for billed sale: produces valid A4 PDF stream")
    void shouldGeneratePdfForBilledSale() {
        SaleItemInput item1 = SaleItemInput.of(switchVariant.id(), Quantity.of(10), Money.ofRupees(120));
        SaleItemInput item2 = SaleItemInput.of(wireVariant.id(), Quantity.of(20), Money.ofRupees(35));
        // Subtotal: 1200 + 700 = 1900. Discount: 100 -> Total: 1800.
        PaymentSplit payments = PaymentSplit.of(
                PaymentInput.of(PaymentMode.UPI, Money.ofRupees(1000), "UPI-TXN-7788"),
                PaymentInput.of(PaymentMode.CASH, Money.ofRupees(800))
        );

        Sale sale = generateBillService.generateBill(
                technicianCustomer.id(),
                LocalDate.of(2026, 10, 18),
                List.of(item1, item2),
                Money.ofRupees(100),
                payments,
                OWNER_USER_ID
        );

        byte[] pdfBytes = billPdfService.generatePdfBytesForSale(sale.id());
        assertNotNull(pdfBytes);
        assertTrue(pdfBytes.length > 1000, "PDF size must be substantial (actual: " + pdfBytes.length + ")");

        // Check PDF header signature "%PDF-"
        String header = new String(pdfBytes, 0, 5, StandardCharsets.US_ASCII);
        assertEquals("%PDF-", header, "File must begin with valid PDF header signature");
    }

    @Test
    @DisplayName("Direct render of BillPrintModel produces valid PDF")
    void shouldDirectlyRenderBillPrintModel() {
        BillPrintModel model = new BillPrintModel(
                BillPrintModel.ShopInfo.defaultShop(),
                "FY26-27/0099",
                LocalDate.of(2026, 10, 18),
                "2026-27",
                BillPrintModel.CustomerInfo.walkIn(),
                List.of(
                        new BillPrintModel.BillLineItem(1, "Havells Switch 6A", "Standard", "Havells", "PIECE", Quantity.of(5), Money.ofRupees(50), Money.ZERO, Money.ofRupees(250))
                ),
                Money.ofRupees(250),
                Money.ZERO,
                Money.ofRupees(250),
                "Two Hundred Fifty Rupees Only",
                List.of(new BillPrintModel.BillPaymentInfo(PaymentMode.CASH, Money.ofRupees(250), null)),
                "Counter Staff",
                null
        );

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        billPdfService.renderPdf(model, baos);
        byte[] bytes = baos.toByteArray();

        assertNotNull(bytes);
        assertTrue(bytes.length > 500);
        assertEquals("%PDF-", new String(bytes, 0, 5, StandardCharsets.US_ASCII));
    }

    @Test
    @DisplayName("B2B Firm Tax Invoice with GSTIN and Payment QR code renders successfully")
    void shouldRenderB2BFirmInvoiceWithUpiQrCode() {
        BillPrintModel.ShopInfo shop = new BillPrintModel.ShopInfo(
                "MAHESHWARI ELECTRICALS - (2025-27)",
                "Chamaliwala Market, Opp. G.P.O., M.I. Road, Jaipur (RAJASTHAN)",
                "Chamaliwala Market, Opp. G.P.O., M.I. Road, Jaipur (RAJASTHAN)",
                "0141-2366620, 2371211 / MOB- 9829053822/9829053866",
                "08AACFM6417B1ZN",
                "Mecombines@rediffmail.Com",
                "Rajasthan",
                "08",
                "AACFM6417B",
                "PUNJAB NATIONAL BANK",
                "00615010000080",
                "PUNB0006110",
                "M.I. ROAD - JAIPUR",
                "9829053822@upi"
        );

        BillPrintModel.CustomerInfo b2bCustomer = new BillPrintModel.CustomerInfo(
                "NAVEEN ELECTRICAL & SANITARY",
                "9461586959",
                "10 / 185 , SAWARN PATH, MANSAROVER, JAIPUR (RAJASTHAN)",
                CustomerType.TECHNICIAN,
                "08ACDPT3960H1ZC",
                "Rajasthan",
                "08"
        );
        assertTrue(b2bCustomer.isFirm());

        List<BillPrintModel.BillLineItem> items = List.of(
                new BillPrintModel.BillLineItem(1, "1.0 BC 1100VPVC INS.IND CBL BLU 90M -SIL", "Standard", "Polycab", "COL", Quantity.of(21), Money.ofPaise(151265), Money.ZERO, Money.ofPaise(3176565), "85446020", 18.0),
                new BillPrintModel.BillLineItem(2, "1.5 BC 1100VPVC INS.IND CBL BLK 90M -SIL", "Standard", "Polycab", "COL", Quantity.of(10), Money.ofPaise(223540), Money.ZERO, Money.ofPaise(2235400), "85446020", 18.0),
                new BillPrintModel.BillLineItem(3, "4.0 BC 1100VPVC INS.IND CBL BLK 90M -SIL", "Standard", "Polycab", "COL", Quantity.of(1), Money.ofPaise(542061), Money.ZERO, Money.ofPaise(542061), "85446020", 18.0)
        );

        Money subtotal = Money.ofPaise(5954026);
        Money discount = Money.ZERO;
        Money total = Money.ofPaise(6558500); // 65,585.00
        String words = IndianNumberToWords.convertPaiseToInrWords(total.getPaise());

        BillPrintModel model = new BillPrintModel(
                shop,
                "2026-27/3032",
                LocalDate.of(2026, 7, 11),
                "2026-27",
                b2bCustomer,
                items,
                subtotal,
                discount,
                total,
                words,
                List.of(new BillPrintModel.BillPaymentInfo(PaymentMode.UPI, total, "UPI-REF-9988")),
                "Counter Cashier",
                "Testing B2B Tax Invoice",
                "DEL-NOTE-44",
                "Hand Delivery",
                "Jaipur",
                "upi://pay?pa=9829053822@upi&pn=MAHESHWARI%20ELECTRICALS&am=65585.00&cu=INR&tn=Invoice%202026-27/3032"
        );

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        billPdfService.renderPdf(model, baos);
        byte[] pdfBytes = baos.toByteArray();

        assertNotNull(pdfBytes);
        assertTrue(pdfBytes.length > 2000, "B2B invoice with QR code should produce complete PDF file");
        assertEquals("%PDF-", new String(pdfBytes, 0, 5, StandardCharsets.US_ASCII));
    }
}
