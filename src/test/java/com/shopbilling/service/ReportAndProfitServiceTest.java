package com.shopbilling.service;

import com.shopbilling.db.BillCounterRepository;
import com.shopbilling.db.BrandRepository;
import com.shopbilling.db.CategoryRepository;
import com.shopbilling.db.ConnectionFactory;
import com.shopbilling.db.CreditNoteCounterRepository;
import com.shopbilling.db.CustomerLedgerRepository;
import com.shopbilling.db.CustomerRepository;
import com.shopbilling.db.DatabaseConfig;
import com.shopbilling.db.ExpenseRepository;
import com.shopbilling.db.FlywayMigrator;
import com.shopbilling.db.PaymentRepository;
import com.shopbilling.db.ProductRepository;
import com.shopbilling.db.ProductVariantRepository;
import com.shopbilling.db.ProfitRepository;
import com.shopbilling.db.PurchaseRepository;
import com.shopbilling.db.ReportRepository;
import com.shopbilling.db.ReturnRepository;
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
import com.shopbilling.model.FinancialYear;
import com.shopbilling.model.Money;
import com.shopbilling.model.PaymentMode;
import com.shopbilling.model.PaymentSplit;
import com.shopbilling.model.Product;
import com.shopbilling.model.ProductProfitRow;
import com.shopbilling.model.ProductVariant;
import com.shopbilling.model.ProfitReportResult;
import com.shopbilling.model.Purchase;
import com.shopbilling.model.PurchaseItem;
import com.shopbilling.model.Quantity;
import com.shopbilling.model.Quarter;
import com.shopbilling.model.ReturnCondition;
import com.shopbilling.model.ReturnItemInput;
import com.shopbilling.model.Sale;
import com.shopbilling.model.SaleItem;
import com.shopbilling.model.SaleItemInput;
import com.shopbilling.model.SaleMode;
import com.shopbilling.model.SalesReportFilter;
import com.shopbilling.model.SalesReportResult;
import com.shopbilling.model.StockMovementType;
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

import static org.junit.jupiter.api.Assertions.*;

class ReportAndProfitServiceTest {

    private ConnectionFactory connectionFactory;
    private TransactionHelper transactionHelper;
    private ReportRepository reportRepository;
    private ProfitRepository profitRepository;
    private ExpenseRepository expenseRepository;
    private PurchaseRepository purchaseRepository;
    private ReportService reportService;
    private ProfitService profitService;
    private GenerateBillService generateBillService;
    private RegisterSaleService registerSaleService;
    private ReturnService returnService;
    private ExpenseService expenseService;
    private StockService stockService;

    private Customer retailCustomer;
    private Customer techCustomer;
    private Supplier anchorSupplier;
    private ProductVariant wireVariant;
    private ProductVariant switchVariant;
    private Brand havells;
    private Category switches;

    private static final long OWNER_USER_ID = 1L;
    private static final long STAFF_USER_ID = 2L;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws Exception {
        Path dbPath = tempDir.resolve("reports_profit_test.db");
        DatabaseConfig config = DatabaseConfig.forFile(dbPath);
        connectionFactory = new ConnectionFactory(config);
        new FlywayMigrator(connectionFactory).migrate();

        transactionHelper = new TransactionHelper(connectionFactory);
        reportRepository = new ReportRepository();
        profitRepository = new ProfitRepository();
        expenseRepository = new ExpenseRepository();
        purchaseRepository = new PurchaseRepository();
        ReturnRepository returnRepository = new ReturnRepository();
        CreditNoteCounterRepository creditNoteCounterRepository = new CreditNoteCounterRepository();
        CustomerLedgerRepository customerLedgerRepository = new CustomerLedgerRepository();
        CustomerRepository customerRepository = new CustomerRepository();
        SupplierRepository supplierRepository = new SupplierRepository();
        PaymentRepository paymentRepository = new PaymentRepository();
        SaleRepository saleRepository = new SaleRepository();
        ProductVariantRepository variantRepository = new ProductVariantRepository();
        ProductRepository productRepository = new ProductRepository();
        UnitRepository unitRepository = new UnitRepository();
        SettingsRepository settingsRepository = new SettingsRepository();
        StockRepository stockRepository = new StockRepository();
        BillCounterRepository billCounterRepository = new BillCounterRepository();
        BrandRepository brandRepo = new BrandRepository();
        CategoryRepository catRepo = new CategoryRepository();

        IstClock clock = IstClock.system();
        BillCounterService billCounterService = new BillCounterService(transactionHelper, billCounterRepository);
        BrandCategoryService brandCategoryService = new BrandCategoryService(transactionHelper, brandRepo, catRepo);
        ProductService productService = new ProductService(transactionHelper, productRepository, variantRepository, brandRepo, catRepo, unitRepository);
        stockService = new StockService(transactionHelper, stockRepository, settingsRepository, variantRepository);
        CustomerSupplierService customerSupplierService = new CustomerSupplierService(transactionHelper, customerRepository, supplierRepository);
        PaymentValidationService paymentValidationService = new PaymentValidationService(transactionHelper, customerRepository);

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

        registerSaleService = new RegisterSaleService(
                transactionHelper,
                saleRepository,
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

        returnService = new ReturnService(
                transactionHelper,
                returnRepository,
                creditNoteCounterRepository,
                saleRepository,
                stockService,
                customerLedgerRepository,
                customerRepository,
                settingsRepository,
                clock
        );

        expenseService = new ExpenseService(transactionHelper, expenseRepository, clock);
        reportService = new ReportService(transactionHelper, reportRepository, clock);
        profitService = new ProfitService(transactionHelper, profitRepository, expenseRepository, clock);

        // Seed users
        try (Connection conn = connectionFactory.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO users (id, username, password_hash, role, full_name) " +
                    "VALUES (1, 'owner', 'hash1', 'OWNER', 'Shop Owner');");
            stmt.execute("INSERT INTO users (id, username, password_hash, role, full_name) " +
                    "VALUES (2, 'staff', 'hash2', 'STAFF', 'Counter Staff');");
        }

        havells = brandCategoryService.createBrand("Havells");
        Brand finolex = brandCategoryService.createBrand("Finolex");
        switches = brandCategoryService.createCategory("Switch");
        Category wires = brandCategoryService.createCategory("Wire");

        Product p1 = productService.createProduct(havells.id(), switches.id(), "Fabio Switch 6A", "PIECE", null);
        switchVariant = productService.addVariant(p1.id(), "White", Money.ofRupees(150), Money.ofRupees(100), "BAR-SW-1");

        Product p2 = productService.createProduct(finolex.id(), wires.id(), "House Wire 1.5 sq mm", "METRE", null);
        wireVariant = productService.addVariant(p2.id(), "Red", Money.ofRupees(40), Money.ofRupees(30), "BAR-WR-1");

        retailCustomer = customerSupplierService.createCustomer("Ramesh Sharma", "9812345678", CustomerType.RETAIL, null, null, null);
        techCustomer = customerSupplierService.createCustomer("Amit Contractor", "9887654321", CustomerType.TECHNICIAN, "Main Road", Money.ofRupees(50000), "Electrician");
        anchorSupplier = customerSupplierService.createSupplier("Anchor Electricals", "011-23456789", "Deepak", "Chawri Bazar", null, null);

        // Record distributor purchases with cost prices (weighted average calculation)
        transactionHelper.execute(conn -> {
            // Batch 1: 50 switches @ Rs 60
            Purchase purch1 = purchaseRepository.insertPurchase(conn, new Purchase(
                    null, anchorSupplier.id(), "INV-001", LocalDate.of(2026, 5, 10),
                    Money.ofRupees(3000), Money.ofRupees(3000), Money.ZERO, "Opening batch 1", null, OWNER_USER_ID, List.of()
            ));
            purchaseRepository.insertPurchaseItem(conn, new PurchaseItem(
                    null, purch1.id(), switchVariant.id(), Quantity.of(50), Money.ofRupees(60), Money.ofRupees(3000)
            ));
            stockService.recordMovementInTransaction(conn, switchVariant.id(), StockMovementType.PURCHASE,
                    Quantity.of(50), "PURCHASE", purch1.id(), "Batch 1", OWNER_USER_ID);

            // Batch 2: 50 switches @ Rs 80
            // Weighted average cost for switchVariant: (50*60 + 50*80) / 100 = Rs 70.00
            Purchase purch2 = purchaseRepository.insertPurchase(conn, new Purchase(
                    null, anchorSupplier.id(), "INV-002", LocalDate.of(2026, 6, 15),
                    Money.ofRupees(4000), Money.ofRupees(4000), Money.ZERO, "Opening batch 2", null, OWNER_USER_ID, List.of()
            ));
            purchaseRepository.insertPurchaseItem(conn, new PurchaseItem(
                    null, purch2.id(), switchVariant.id(), Quantity.of(50), Money.ofRupees(80), Money.ofRupees(4000)
            ));
            stockService.recordMovementInTransaction(conn, switchVariant.id(), StockMovementType.PURCHASE,
                    Quantity.of(50), "PURCHASE", purch2.id(), "Batch 2", OWNER_USER_ID);

            // Wire purchase: 100 metres @ Rs 20
            Purchase purch3 = purchaseRepository.insertPurchase(conn, new Purchase(
                    null, anchorSupplier.id(), "INV-003", LocalDate.of(2026, 7, 20),
                    Money.ofRupees(2000), Money.ofRupees(2000), Money.ZERO, "Wire stock", null, OWNER_USER_ID, List.of()
            ));
            purchaseRepository.insertPurchaseItem(conn, new PurchaseItem(
                    null, purch3.id(), wireVariant.id(), Quantity.of(100), Money.ofRupees(20), Money.ofRupees(2000)
            ));
            stockService.recordMovementInTransaction(conn, wireVariant.id(), StockMovementType.PURCHASE,
                    Quantity.of(100), "PURCHASE", purch3.id(), "Wire batch", OWNER_USER_ID);
            return null;
        });
    }

    @Test
    @DisplayName("ReportService: generate sales report with filters and period aggregations")
    void testSalesReportWithFilters() {
        // Sale 1: Billed sale on 2026-06-10 with Cash payment
        Sale sale1 = generateBillService.generateBill(
                retailCustomer.id(),
                LocalDate.of(2026, 6, 10),
                List.of(new SaleItemInput(switchVariant.id(), Quantity.of(10), Money.ofRupees(100), Money.ZERO)),
                Money.ZERO,
                PaymentSplit.single(PaymentMode.CASH, Money.ofRupees(1000)),
                OWNER_USER_ID
        );

        // Sale 2: Registered sale on 2026-07-05 with UPI payment
        Sale sale2 = registerSaleService.registerSale(
                techCustomer.id(),
                LocalDate.of(2026, 7, 5),
                List.of(new SaleItemInput(wireVariant.id(), Quantity.of(20), Money.ofRupees(30), Money.ZERO)),
                Money.ZERO,
                PaymentSplit.single(PaymentMode.UPI, Money.ofRupees(600)),
                STAFF_USER_ID
        );

        // Sale 3: Billed sale on 2026-08-15 with Credit payment to technician
        Sale sale3 = generateBillService.generateBill(
                techCustomer.id(),
                LocalDate.of(2026, 8, 15),
                List.of(new SaleItemInput(switchVariant.id(), Quantity.of(5), Money.ofRupees(100), Money.ZERO)),
                Money.ZERO,
                PaymentSplit.single(PaymentMode.CREDIT, Money.ofRupees(500)),
                OWNER_USER_ID
        );

        // 1. All sales query
        SalesReportResult allReport = reportService.generateSalesReport(SalesReportFilter.all());
        assertEquals(3, allReport.totalTransactions());
        assertEquals(2, allReport.billedTransactions());
        assertEquals(1, allReport.registeredTransactions());
        assertEquals(Money.ofRupees(2100), allReport.grossSales());
        assertEquals(Money.ZERO, allReport.totalReturns());
        assertEquals(Money.ofRupees(2100), allReport.netSales());
        assertEquals(Money.ofRupees(1000), allReport.paymentModeTotals().get(PaymentMode.CASH));
        assertEquals(Money.ofRupees(600), allReport.paymentModeTotals().get(PaymentMode.UPI));
        assertEquals(Money.ofRupees(500), allReport.paymentModeTotals().get(PaymentMode.CREDIT));

        // 2. Filter by SaleMode: BILL only
        SalesReportResult billOnlyReport = reportService.generateSalesReport(
                SalesReportFilter.all().withSaleMode(SaleMode.BILL)
        );
        assertEquals(2, billOnlyReport.totalTransactions());
        assertEquals(Money.ofRupees(1500), billOnlyReport.grossSales());

        // 3. Filter by CustomerType: TECHNICIAN only
        SalesReportResult techOnlyReport = reportService.generateSalesReport(
                SalesReportFilter.all().withCustomerType(CustomerType.TECHNICIAN)
        );
        assertEquals(2, techOnlyReport.totalTransactions());
        assertEquals(Money.ofRupees(1100), techOnlyReport.grossSales());

        // 4. Filter by Brand: Havells only
        SalesReportResult havellsOnlyReport = reportService.generateSalesReport(
                SalesReportFilter.all().withBrandId(havells.id())
        );
        assertEquals(2, havellsOnlyReport.totalTransactions());

        // 5. Quarterly Report: Q1 (Apr-Jun 2026) has only Sale 1
        FinancialYear fy26 = FinancialYear.of(2026);
        SalesReportResult q1Report = reportService.generateQuarterlyReport(fy26, Quarter.Q1);
        assertEquals(1, q1Report.totalTransactions());
        assertEquals(Money.ofRupees(1000), q1Report.grossSales());

        // Q2 (Jul-Sep 2026) has Sale 2 and Sale 3
        SalesReportResult q2Report = reportService.generateQuarterlyReport(fy26, Quarter.Q2);
        assertEquals(2, q2Report.totalTransactions());
        assertEquals(Money.ofRupees(1100), q2Report.grossSales());

        // Yearly Report
        SalesReportResult yearlyReport = reportService.generateYearlyReport(fy26);
        assertEquals(3, yearlyReport.totalTransactions());
        assertEquals(Money.ofRupees(2100), yearlyReport.grossSales());
    }

    @Test
    @DisplayName("ReportService: Net sales decreases after customer returns")
    void testNetSalesWithReturns() {
        // Sale: 10 switches @ Rs 100 = Rs 1000
        Sale sale = generateBillService.generateBill(
                retailCustomer.id(),
                LocalDate.of(2026, 6, 10),
                List.of(new SaleItemInput(switchVariant.id(), Quantity.of(10), Money.ofRupees(100), Money.ZERO)),
                Money.ZERO,
                PaymentSplit.single(PaymentMode.CASH, Money.ofRupees(1000)),
                OWNER_USER_ID
        );

        SaleItem saleItem = sale.items().get(0);

        // Return 2 switches (refund Rs 200)
        returnService.processReturn(
                sale.id(),
                LocalDate.of(2026, 6, 12),
                List.of(new ReturnItemInput(saleItem.id(), Quantity.of(2), ReturnCondition.SELLABLE, "Customer changed mind")),
                PaymentMode.CASH,
                "Return test",
                OWNER_USER_ID
        );

        SalesReportResult report = reportService.generateSalesReport(SalesReportFilter.all());
        assertEquals(1, report.totalTransactions());
        assertEquals(Money.ofRupees(1000), report.grossSales());
        assertEquals(Money.ofRupees(200), report.totalReturns());
        assertEquals(Money.ofRupees(800), report.netSales());
    }

    @Test
    @DisplayName("ReportService: Export to CSV contains expected headers, rows and summary")
    void testExportToCsv() {
        generateBillService.generateBill(
                retailCustomer.id(),
                LocalDate.of(2026, 6, 10),
                List.of(new SaleItemInput(switchVariant.id(), Quantity.of(10), Money.ofRupees(100), Money.ZERO)),
                Money.ZERO,
                PaymentSplit.single(PaymentMode.CASH, Money.ofRupees(1000)),
                OWNER_USER_ID
        );

        SalesReportResult report = reportService.generateSalesReport(SalesReportFilter.all());
        String csv = reportService.exportToCsvString(report);

        assertNotNull(csv);
        assertTrue(csv.contains("Sale ID,Sale Mode,Bill Number,Date,Customer,Customer Type,Subtotal (Rs),Discount (Rs),Total (Rs),Payment Modes"));
        assertTrue(csv.contains("Ramesh Sharma"));
        assertTrue(csv.contains("1000.00"));
        assertTrue(csv.contains("--- SUMMARY ---"));
        assertTrue(csv.contains("Gross Sales (Rs),1000.00"));
        assertTrue(csv.contains("Net Sales (Rs),1000.00"));
    }

    @Test
    @DisplayName("ProfitService: Owner-only permission check")
    void testProfitOwnerPermission() {
        LocalDate date = LocalDate.of(2026, 6, 15);
        // Staff user cannot view profit report
        ValidationException ex = assertThrows(ValidationException.class, () ->
                profitService.generateProfitReport(STAFF_USER_ID, date, date)
        );
        assertTrue(ex.getMessage().contains("Only the shop owner is authorized"));

        // Owner user succeeds
        ProfitReportResult result = profitService.generateProfitReport(OWNER_USER_ID, date, date);
        assertNotNull(result);
    }

    @Test
    @DisplayName("ProfitService: Weighted average cost, COGS, returns, and operating expenses")
    void testWeightedAverageCostAndProfit() {
        // Switch variant had two purchase batches:
        // Batch 1: 50 units @ Rs 60
        // Batch 2: 50 units @ Rs 80
        // Weighted average unit cost = (50*60 + 50*80) / 100 = Rs 70.00

        // Sell 10 switches @ Rs 100 on 2026-06-10 (Gross revenue = Rs 1000)
        Sale sale = generateBillService.generateBill(
                retailCustomer.id(),
                LocalDate.of(2026, 6, 10),
                List.of(new SaleItemInput(switchVariant.id(), Quantity.of(10), Money.ofRupees(100), Money.ZERO)),
                Money.ZERO,
                PaymentSplit.single(PaymentMode.CASH, Money.ofRupees(1000)),
                OWNER_USER_ID
        );

        // Return 2 switches on 2026-06-12 (refund = Rs 200)
        SaleItem item = sale.items().get(0);
        returnService.processReturn(
                sale.id(),
                LocalDate.of(2026, 6, 12),
                List.of(new ReturnItemInput(item.id(), Quantity.of(2), ReturnCondition.SELLABLE, "Surplus")),
                PaymentMode.CASH,
                "Return",
                OWNER_USER_ID
        );

        // Record operating expenses in the same period: Rs 100 (Snacks)
        expenseService.recordExpense(
                1L, // TEA_SNACKS or default category
                Money.ofRupees(100),
                PaymentMode.CASH,
                LocalDate.of(2026, 6, 11),
                "Snacks for electrician meet",
                OWNER_USER_ID
        );

        // Generate Owner Profit Report for June 2026
        ProfitReportResult profitReport = profitService.generateProfitReport(
                OWNER_USER_ID,
                LocalDate.of(2026, 6, 1),
                LocalDate.of(2026, 6, 30)
        );

        assertNotNull(profitReport);
        assertEquals(1, profitReport.productProfits().size());

        ProductProfitRow switchProfit = profitReport.productProfits().get(0);
        assertEquals(switchVariant.id(), switchProfit.variantId());
        assertEquals(Quantity.of(8), switchProfit.quantitySold()); // 10 sold - 2 returned = 8 net
        assertEquals(Money.ofRupees(800), switchProfit.revenue()); // 1000 - 200 = 800 net
        assertEquals(Money.ofRupees(70), switchProfit.weightedAvgUnitCost()); // Weighted cost = Rs 70.00

        // COGS = 8 * Rs 70 = Rs 560.00
        assertEquals(Money.ofRupees(560), switchProfit.cogs());
        // Gross margin = Rs 800 - Rs 560 = Rs 240.00
        assertEquals(Money.ofRupees(240), switchProfit.grossMargin());
        // Gross margin % = (240 / 800) * 100 = 30.0%
        assertEquals(30.0, switchProfit.marginPercent(), 0.001);

        // Aggregate summary figures
        assertEquals(Money.ofRupees(800), profitReport.totalRevenue());
        assertEquals(Money.ofRupees(560), profitReport.totalCogs());
        assertEquals(Money.ofRupees(240), profitReport.grossProfit());
        assertEquals(Money.ofRupees(100), profitReport.totalExpenses());
        // Net profit = Gross profit (240) - Expenses (100) = Rs 140.00
        assertEquals(Money.ofRupees(140), profitReport.netProfit());
        // Net margin % = (140 / 800) * 100 = 17.5%
        assertEquals(17.5, profitReport.netMarginPercent(), 0.001);

        // Verify costing documentation
        String doc = profitService.getCostingMethodDocumentation();
        assertNotNull(doc);
        assertTrue(doc.contains("Weighted Average Purchase Cost"));
    }
}
