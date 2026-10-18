package com.shopbilling.service;

import com.shopbilling.db.BillCounterRepository;
import com.shopbilling.db.BrandRepository;
import com.shopbilling.db.CategoryRepository;
import com.shopbilling.db.ConnectionFactory;
import com.shopbilling.db.CreditNoteCounterRepository;
import com.shopbilling.db.CustomerLedgerRepository;
import com.shopbilling.db.CustomerRepository;
import com.shopbilling.db.DatabaseConfig;
import com.shopbilling.db.DayEndClosingRepository;
import com.shopbilling.db.ExpenseRepository;
import com.shopbilling.db.FlywayMigrator;
import com.shopbilling.db.PaymentRepository;
import com.shopbilling.db.ProductRepository;
import com.shopbilling.db.ProductVariantRepository;
import com.shopbilling.db.ReturnRepository;
import com.shopbilling.db.SaleRepository;
import com.shopbilling.db.SettingsRepository;
import com.shopbilling.db.StockRepository;
import com.shopbilling.db.SupplierLedgerRepository;
import com.shopbilling.db.SupplierRepository;
import com.shopbilling.db.TransactionHelper;
import com.shopbilling.db.UnitRepository;
import com.shopbilling.model.Brand;
import com.shopbilling.model.Category;
import com.shopbilling.model.Customer;
import com.shopbilling.model.CustomerType;
import com.shopbilling.model.DayEndCashSummary;
import com.shopbilling.model.DayEndClosingRecord;
import com.shopbilling.model.ExpenseCategory;
import com.shopbilling.model.ExpenseRecord;
import com.shopbilling.model.Money;
import com.shopbilling.model.PaymentMode;
import com.shopbilling.model.PaymentSplit;
import com.shopbilling.model.Product;
import com.shopbilling.model.ProductVariant;
import com.shopbilling.model.Quantity;
import com.shopbilling.model.ReturnCondition;
import com.shopbilling.model.ReturnItemInput;
import com.shopbilling.model.Sale;
import com.shopbilling.model.SaleItem;
import com.shopbilling.model.SaleItemInput;
import com.shopbilling.model.StockMovementType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ExpenseAndDayEndClosingTest {

    private ConnectionFactory connectionFactory;
    private TransactionHelper transactionHelper;
    private ExpenseRepository expenseRepository;
    private DayEndClosingRepository dayEndClosingRepository;
    private CustomerLedgerRepository customerLedgerRepository;
    private SaleRepository saleRepository;
    private GenerateBillService generateBillService;
    private ReturnService returnService;
    private CreditLedgerService creditLedgerService;
    private ExpenseService expenseService;
    private DayEndClosingService dayEndClosingService;

    private Customer technicianCustomer;
    private Customer retailCustomer;
    private ProductVariant switchVariant;

    private static final long OWNER_USER_ID = 1L;
    private static final long STAFF_USER_ID = 2L;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws Exception {
        Path dbPath = tempDir.resolve("expense_closing_test.db");
        DatabaseConfig config = DatabaseConfig.forFile(dbPath);
        connectionFactory = new ConnectionFactory(config);
        new FlywayMigrator(connectionFactory).migrate();

        transactionHelper = new TransactionHelper(connectionFactory);
        expenseRepository = new ExpenseRepository();
        dayEndClosingRepository = new DayEndClosingRepository();
        customerLedgerRepository = new CustomerLedgerRepository();
        saleRepository = new SaleRepository();
        ReturnRepository returnRepository = new ReturnRepository();
        CreditNoteCounterRepository creditNoteCounterRepository = new CreditNoteCounterRepository();
        CustomerRepository customerRepository = new CustomerRepository();
        SupplierRepository supplierRepository = new SupplierRepository();
        PaymentRepository paymentRepository = new PaymentRepository();
        ProductVariantRepository variantRepository = new ProductVariantRepository();
        ProductRepository productRepository = new ProductRepository();
        UnitRepository unitRepository = new UnitRepository();
        SettingsRepository settingsRepository = new SettingsRepository();
        StockRepository stockRepository = new StockRepository();
        BillCounterRepository billCounterRepository = new BillCounterRepository();
        BillCounterService billCounterService = new BillCounterService(transactionHelper, billCounterRepository);
        BrandRepository brandRepo = new BrandRepository();
        CategoryRepository catRepo = new CategoryRepository();

        BrandCategoryService brandCategoryService = new BrandCategoryService(transactionHelper, brandRepo, catRepo);
        ProductService productService = new ProductService(transactionHelper, productRepository, variantRepository, brandRepo, catRepo, unitRepository);
        StockService stockService = new StockService(transactionHelper, stockRepository, settingsRepository, variantRepository);
        CustomerSupplierService customerSupplierService = new CustomerSupplierService(transactionHelper, customerRepository, supplierRepository);
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

        SupplierLedgerRepository supplierLedgerRepository = new SupplierLedgerRepository();
        creditLedgerService = new CreditLedgerService(
                transactionHelper,
                customerLedgerRepository,
                supplierLedgerRepository,
                customerRepository,
                supplierRepository,
                saleRepository,
                clock
        );

        expenseService = new ExpenseService(transactionHelper, expenseRepository, clock);
        dayEndClosingService = new DayEndClosingService(transactionHelper, dayEndClosingRepository, expenseRepository, clock);

        // Seed users
        try (Connection conn = connectionFactory.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO users (id, username, password_hash, role, full_name) " +
                    "VALUES (1, 'owner', 'hash1', 'OWNER', 'Shop Owner');");
            stmt.execute("INSERT INTO users (id, username, password_hash, role, full_name) " +
                    "VALUES (2, 'staff', 'hash2', 'STAFF', 'Counter Staff');");
        }

        Brand havells = brandCategoryService.createBrand("Havells");
        Category switches = brandCategoryService.createCategory("Switch");

        Product p1 = productService.createProduct(havells.id(), switches.id(), "Fabio Switch", "PIECE", null);
        switchVariant = productService.addVariant(p1.id(), "White", Money.ofRupees(150), Money.ofRupees(100), "BAR-SW1");

        retailCustomer = customerSupplierService.createCustomer("Mohan Sharma", "9898000000", CustomerType.RETAIL, null, null, null);
        technicianCustomer = customerSupplierService.createCustomer("Ravi Contractor", "9876543210", CustomerType.TECHNICIAN, "Civil Lines", Money.ofRupees(10000), "Regular technician");

        // Seed stock: 100 switches
        stockService.recordMovement(switchVariant.id(), StockMovementType.PURCHASE,
                Quantity.of(100), "PURCHASE", 1, "Opening Stock", OWNER_USER_ID);
    }

    @Test
    @DisplayName("Expenses: create categories and record expenses")
    void testExpenses() {
        List<ExpenseCategory> categories = expenseService.getAllCategories();
        assertTrue(categories.size() >= 6, "Must contain default categories");

        // Add custom category
        ExpenseCategory repairs = expenseService.createCategory("SHOP_REPAIRS");
        assertNotNull(repairs.id());
        assertEquals("SHOP_REPAIRS", repairs.name());

        // Duplicate category rejected
        assertThrows(DuplicateEntityException.class, () -> expenseService.createCategory("SHOP_REPAIRS"));

        // Record cash expense
        LocalDate testDate = LocalDate.of(2026, 10, 18);
        ExpenseRecord exp1 = expenseService.recordExpense(
                repairs.id(),
                Money.ofRupees(250),
                PaymentMode.CASH,
                testDate,
                "Fixed front shutter lock",
                STAFF_USER_ID
        );
        assertNotNull(exp1.id());
        assertEquals(Money.ofRupees(250), exp1.amount());
        assertEquals(PaymentMode.CASH, exp1.paymentMode());

        // Query expenses
        List<ExpenseRecord> dayExpenses = expenseService.getExpensesForDate(testDate);
        assertEquals(1, dayExpenses.size());
        assertEquals("SHOP_REPAIRS", dayExpenses.get(0).categoryName());
    }

    @Test
    @DisplayName("Daily cash reconciliation formula (FR-38): Expected = Cash sales - Cash refunds + Cash payments - Cash expenses")
    void shouldReconcileDailyCashAccurately() {
        LocalDate date = LocalDate.of(2026, 10, 18);

        // 1. Cash sale of 10 switches @ 100 = Rs 1000 cash
        Sale bill1 = generateBillService.generateBill(
                retailCustomer.id(),
                date,
                List.of(SaleItemInput.of(switchVariant.id(), Quantity.of(10), Money.ofRupees(100))),
                Money.ZERO,
                PaymentSplit.single(PaymentMode.CASH, Money.ofRupees(1000)),
                OWNER_USER_ID
        );

        // 2. UPI sale of 5 switches = Rs 500 (UPI MUST NOT be included in cash drawer!)
        generateBillService.generateBill(
                retailCustomer.id(),
                date,
                List.of(SaleItemInput.of(switchVariant.id(), Quantity.of(5), Money.ofRupees(100))),
                Money.ZERO,
                PaymentSplit.single(PaymentMode.UPI, Money.ofRupees(500), "UPI-999"),
                OWNER_USER_ID
        );

        // 3. Cash refund: return 2 switches from bill1 = Rs 200 cash refund
        SaleItem saleItem = bill1.items().get(0);
        returnService.processReturn(
                bill1.id(),
                date,
                List.of(ReturnItemInput.of(saleItem.id(), Quantity.of(2), ReturnCondition.SELLABLE, "Customer return")),
                PaymentMode.CASH,
                "Cash refund",
                OWNER_USER_ID
        );

        // 4. Cash customer payment received on credit dues: Rs 500
        creditLedgerService.recordCustomerPayment(
                technicianCustomer.id(),
                Money.ofRupees(500),
                PaymentMode.CASH,
                null,
                date,
                null,
                "Old dues cleared",
                OWNER_USER_ID
        );

        // 5. Cash expense: Rs 50 tea/snacks
        ExpenseCategory teaCat = expenseService.getAllCategories().stream()
                .filter(c -> "TEA_SNACKS".equals(c.name()))
                .findFirst().orElseThrow();
        expenseService.recordExpense(teaCat.id(), Money.ofRupees(50), PaymentMode.CASH, date, "Tea for staff", OWNER_USER_ID);

        // FORMULA RECONCILIATION:
        // Cash Sales: 1000
        // Cash Refunds: 200
        // Cash Payments: 500
        // Cash Expenses: 50
        // Expected Cash = 1000 - 200 + 500 - 50 = 1250
        DayEndCashSummary summary = dayEndClosingService.calculateDailyCashSummary(date);

        assertEquals(Money.ofRupees(1000), summary.cashSales());
        assertEquals(Money.ofRupees(200), summary.cashRefunds());
        assertEquals(Money.ofRupees(500), summary.cashCustomerPayments());
        assertEquals(Money.ofRupees(50), summary.cashExpenses());
        assertEquals(Money.ofRupees(1250), summary.expectedCash());
    }

    @Test
    @DisplayName("Day-end closing: owner records counted cash and difference is calculated")
    void shouldCloseDayAndRecordVariance() {
        LocalDate date = LocalDate.of(2026, 10, 18);

        // Cash sale: Rs 500
        generateBillService.generateBill(
                retailCustomer.id(),
                date,
                List.of(SaleItemInput.of(switchVariant.id(), Quantity.of(5), Money.ofRupees(100))),
                Money.ZERO,
                PaymentSplit.single(PaymentMode.CASH, Money.ofRupees(500)),
                OWNER_USER_ID
        );

        // Expected cash = 500. Owner counted Rs 490 in the till (Rs 10 shortage).
        DayEndClosingRecord closing = dayEndClosingService.closeDay(
                date,
                Money.ofRupees(490),
                "Rs 10 change short at counter",
                OWNER_USER_ID
        );

        assertNotNull(closing.id());
        assertEquals(date, closing.closingDate());
        assertEquals(Money.ofRupees(500), closing.expectedCash());
        assertEquals(Money.ofRupees(490), closing.countedCash());
        assertEquals(Money.ofRupees(-10), closing.difference());
        assertEquals(OWNER_USER_ID, closing.closedByUserId());

        // Verify retrieval
        Optional<DayEndClosingRecord> fetched = dayEndClosingService.getClosingForDate(date);
        assertTrue(fetched.isPresent());
        assertEquals(Money.ofRupees(-10), fetched.get().difference());

        // Closing same day again must be rejected
        ValidationException dupEx = assertThrows(ValidationException.class, () ->
                dayEndClosingService.closeDay(date, Money.ofRupees(500), "Duplicate", OWNER_USER_ID)
        );
        assertTrue(dupEx.getMessage().contains("already closed"));
    }

    @Test
    @DisplayName("Staff cannot close the day")
    void shouldPreventStaffFromClosingDay() {
        LocalDate date = LocalDate.of(2026, 10, 18);

        ValidationException ex = assertThrows(ValidationException.class, () ->
                dayEndClosingService.closeDay(date, Money.ofRupees(100), "Staff attempt", STAFF_USER_ID)
        );
        assertTrue(ex.getMessage().contains("Only the shop owner is authorized"));
    }
}
