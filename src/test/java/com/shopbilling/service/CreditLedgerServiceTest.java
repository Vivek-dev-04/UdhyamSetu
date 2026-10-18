package com.shopbilling.service;

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
import com.shopbilling.db.PurchaseRepository;
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
import com.shopbilling.model.CustomerCreditStatement;
import com.shopbilling.model.CustomerDuesSummary;
import com.shopbilling.model.CustomerLedgerEntry;
import com.shopbilling.model.CustomerType;
import com.shopbilling.model.Money;
import com.shopbilling.model.PaymentInput;
import com.shopbilling.model.PaymentMode;
import com.shopbilling.model.PaymentSplit;
import com.shopbilling.model.Product;
import com.shopbilling.model.ProductVariant;
import com.shopbilling.model.PurchaseItemInput;
import com.shopbilling.model.Quantity;
import com.shopbilling.model.Sale;
import com.shopbilling.model.SaleAllocationInput;
import com.shopbilling.model.SaleItemInput;
import com.shopbilling.model.Supplier;
import com.shopbilling.model.SupplierLedgerEntry;
import com.shopbilling.model.SupplierPayableSummary;
import com.shopbilling.model.SupplierStatement;
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

class CreditLedgerServiceTest {

    private ConnectionFactory connectionFactory;
    private TransactionHelper transactionHelper;
    private CustomerLedgerRepository customerLedgerRepository;
    private SupplierLedgerRepository supplierLedgerRepository;
    private CustomerRepository customerRepository;
    private SupplierRepository supplierRepository;
    private SaleRepository saleRepository;
    private GenerateBillService generateBillService;
    private StockInService stockInService;
    private CreditLedgerService creditLedgerService;

    private Customer technicianCustomer;
    private Customer retailCustomer;
    private Supplier havellsDistributor;
    private ProductVariant switchVariant;

    private static final long OWNER_USER_ID = 1L;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws Exception {
        Path dbPath = tempDir.resolve("credit_ledger_test.db");
        DatabaseConfig config = DatabaseConfig.forFile(dbPath);
        connectionFactory = new ConnectionFactory(config);
        new FlywayMigrator(connectionFactory).migrate();

        transactionHelper = new TransactionHelper(connectionFactory);
        customerLedgerRepository = new CustomerLedgerRepository();
        supplierLedgerRepository = new SupplierLedgerRepository();
        customerRepository = new CustomerRepository();
        supplierRepository = new SupplierRepository();
        saleRepository = new SaleRepository();
        PaymentRepository paymentRepository = new PaymentRepository();
        ProductVariantRepository variantRepository = new ProductVariantRepository();
        ProductRepository productRepository = new ProductRepository();
        UnitRepository unitRepository = new UnitRepository();
        SettingsRepository settingsRepository = new SettingsRepository();
        StockRepository stockRepository = new StockRepository();
        BillCounterRepository billCounterRepository = new BillCounterRepository();
        BillCounterService billCounterService = new BillCounterService(transactionHelper, billCounterRepository);
        PurchaseRepository purchaseRepository = new PurchaseRepository();
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

        stockInService = new StockInService(
                transactionHelper,
                purchaseRepository,
                stockService,
                supplierRepository,
                supplierLedgerRepository,
                variantRepository
        );

        creditLedgerService = new CreditLedgerService(
                transactionHelper,
                customerLedgerRepository,
                supplierLedgerRepository,
                customerRepository,
                supplierRepository,
                saleRepository,
                clock
        );

        // Seed owner user
        try (Connection conn = connectionFactory.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO users (id, username, password_hash, role, full_name) " +
                    "VALUES (1, 'owner', 'hash1', 'OWNER', 'Shop Owner');");
        }

        Brand havells = brandCategoryService.createBrand("Havells");
        Category switches = brandCategoryService.createCategory("Switch");

        Product p1 = productService.createProduct(havells.id(), switches.id(), "Fabio Switch", "PIECE", null);
        switchVariant = productService.addVariant(p1.id(), "White", Money.ofRupees(150), Money.ofRupees(120), "BAR-SW1");

        technicianCustomer = customerSupplierService.createCustomer("Ravi Contractor", "9876543210", CustomerType.TECHNICIAN, "Civil Lines", Money.ofRupees(20000), "Regular technician");
        retailCustomer = customerSupplierService.createCustomer("Mohan Sharma", "9898989898", CustomerType.RETAIL, null, null, null);

        havellsDistributor = customerSupplierService.createSupplier("National Electrical Agency", "9123456789", "Mr. Gupta", "Industrial Area", "23AABCU9603R1ZM", "Authorised Havells Distributor");
    }

    @Test
    @DisplayName("Record customer payment: reduces dues and appends ledger entry")
    void shouldRecordCustomerPayment() {
        // Initial credit sale: 10 switches = 1200 on credit
        stockInService.recordStockIn(
                havellsDistributor.id(),
                "INV-001",
                LocalDate.of(2026, 10, 1),
                List.of(new PurchaseItemInput(switchVariant.id(), Quantity.of(50), Money.ofRupees(80))),
                Money.ofRupees(4000), // Paid in full
                null,
                OWNER_USER_ID
        );

        SaleItemInput item = SaleItemInput.of(switchVariant.id(), Quantity.of(10), Money.ofRupees(120));
        PaymentSplit payments = PaymentSplit.single(PaymentMode.CREDIT, Money.ofRupees(1200));

        generateBillService.generateBill(
                technicianCustomer.id(),
                LocalDate.of(2026, 10, 5),
                List.of(item),
                Money.ZERO,
                payments,
                OWNER_USER_ID
        );

        assertEquals(Money.ofRupees(1200), creditLedgerService.getCustomerCurrentDues(technicianCustomer.id()));

        // Customer pays 500 cash
        CustomerLedgerEntry entry = creditLedgerService.recordCustomerPayment(
                technicianCustomer.id(),
                Money.ofRupees(500),
                PaymentMode.CASH,
                null,
                LocalDate.of(2026, 10, 10),
                null,
                "Part payment",
                OWNER_USER_ID
        );

        assertNotNull(entry.id());
        assertEquals("PAYMENT_RECEIVED", entry.entryType());
        assertEquals(Money.ofRupees(-500), entry.amount());

        // Dues should now be 700
        assertEquals(Money.ofRupees(700), creditLedgerService.getCustomerCurrentDues(technicianCustomer.id()));
    }

    @Test
    @DisplayName("Payment allocation to specific sale: validates and records allocation")
    void shouldAllocatePaymentToSpecificSale() {
        stockInService.recordStockIn(
                havellsDistributor.id(),
                "INV-002",
                LocalDate.of(2026, 10, 1),
                List.of(new PurchaseItemInput(switchVariant.id(), Quantity.of(50), Money.ofRupees(80))),
                Money.ofRupees(4000),
                null,
                OWNER_USER_ID
        );

        Sale bill = generateBillService.generateBill(
                technicianCustomer.id(),
                LocalDate.of(2026, 10, 5),
                List.of(SaleItemInput.of(switchVariant.id(), Quantity.of(5), Money.ofRupees(120))),
                Money.ZERO,
                PaymentSplit.single(PaymentMode.CREDIT, Money.ofRupees(600)),
                OWNER_USER_ID
        );

        // Allocate Rs 400 of a Rs 500 payment specifically to bill.id()
        List<SaleAllocationInput> allocations = List.of(SaleAllocationInput.of(bill.id(), Money.ofRupees(400)));

        CustomerLedgerEntry paymentEntry = creditLedgerService.recordCustomerPayment(
                technicianCustomer.id(),
                Money.ofRupees(500),
                PaymentMode.UPI,
                "UPI-ALLOC-123",
                LocalDate.of(2026, 10, 10),
                allocations,
                "Allocated payment",
                OWNER_USER_ID
        );

        assertNotNull(paymentEntry.id());
        assertEquals(Money.ofRupees(100), creditLedgerService.getCustomerCurrentDues(technicianCustomer.id()));
    }

    @Test
    @DisplayName("Record supplier payment: reduces distributor payable")
    void shouldRecordSupplierPayment() {
        // Purchase on credit: Rs 4000 total, Rs 1000 paid, Rs 3000 on credit
        stockInService.recordStockIn(
                havellsDistributor.id(),
                "INV-SUPP-1",
                LocalDate.of(2026, 10, 1),
                List.of(new PurchaseItemInput(switchVariant.id(), Quantity.of(50), Money.ofRupees(80))),
                Money.ofRupees(1000),
                null,
                OWNER_USER_ID
        );

        assertEquals(Money.ofRupees(3000), creditLedgerService.getSupplierCurrentPayable(havellsDistributor.id()));

        // Pay distributor Rs 2000 via NEFT/UPI
        SupplierLedgerEntry entry = creditLedgerService.recordSupplierPayment(
                havellsDistributor.id(),
                Money.ofRupees(2000),
                PaymentMode.UPI,
                "NEFT-998877",
                LocalDate.of(2026, 10, 15),
                "On-account payment",
                OWNER_USER_ID
        );

        assertNotNull(entry.id());
        assertEquals(Money.ofRupees(1000), creditLedgerService.getSupplierCurrentPayable(havellsDistributor.id()));
    }

    @Test
    @DisplayName("FIFO Aging analysis: accurately categorises outstanding debt into 0-30, 31-60, 61-90, 90+ buckets")
    void shouldAccuratelyCalculateFifoAging() throws Exception {
        LocalDate asOfDate = LocalDate.of(2026, 10, 18);

        // Seed 3 credit sales directly into customer ledger at various historical dates
        // 1. 100 days ago (2026-07-10): Rs 1000  (Bucket: 90+ days)
        // 2. 45 days ago  (2026-09-03): Rs 2000  (Bucket: 31-60 days)
        // 3. 10 days ago  (2026-10-08): Rs 3000  (Bucket: 0-30 days)
        // Total credit = Rs 6000.
        try (Connection conn = connectionFactory.getConnection()) {
            customerLedgerRepository.insertEntry(conn, technicianCustomer.id(), "CREDIT_SALE", 100000, "SALE", 1, "2026-07-10", "Old sale", OWNER_USER_ID);
            customerLedgerRepository.insertEntry(conn, technicianCustomer.id(), "CREDIT_SALE", 200000, "SALE", 2, "2026-09-03", "Medium sale", OWNER_USER_ID);
            customerLedgerRepository.insertEntry(conn, technicianCustomer.id(), "CREDIT_SALE", 300000, "SALE", 3, "2026-10-08", "Recent sale", OWNER_USER_ID);
        }

        // Customer pays Rs 1500.
        // FIFO applies Rs 1500 against the oldest debt:
        // - Rs 1000 clears the 100-day old debt completely (remaining 90+ days = 0)
        // - Rs 500 clears part of the 45-day old debt (remaining 31-60 days = 2000 - 500 = 1500)
        // - The 10-day old debt (Rs 3000) is untouched (0-30 days = 3000)
        creditLedgerService.recordCustomerPayment(
                technicianCustomer.id(),
                Money.ofRupees(1500),
                PaymentMode.CASH,
                null,
                LocalDate.of(2026, 10, 15),
                null,
                "Partial clearing",
                OWNER_USER_ID
        );

        CustomerCreditStatement statement = creditLedgerService.getCustomerStatement(technicianCustomer.id(), asOfDate);

        assertNotNull(statement);
        assertEquals(Money.ofRupees(4500), statement.currentDues());
        assertEquals(Money.ofRupees(20000), statement.creditLimit());
        assertEquals(Money.ofRupees(15500), statement.availableCredit()); // 20000 - 4500

        // Check aging buckets
        assertEquals(Money.ofRupees(3000), statement.aging().days0To30(), "0-30 days must be Rs 3000");
        assertEquals(Money.ofRupees(1500), statement.aging().days31To60(), "31-60 days must be Rs 1500");
        assertEquals(Money.ZERO, statement.aging().days61To90(), "61-90 days must be Rs 0");
        assertEquals(Money.ZERO, statement.aging().days90Plus(), "90+ days must be Rs 0 (cleared by FIFO)");
        assertEquals(Money.ofRupees(4500), statement.aging().totalOutstanding());
    }

    @Test
    @DisplayName("Lists all customers with outstanding dues sorted descending")
    void shouldListAllCustomersWithOutstandingDues() throws Exception {
        try (Connection conn = connectionFactory.getConnection()) {
            customerLedgerRepository.insertEntry(conn, technicianCustomer.id(), "CREDIT_SALE", 500000, "SALE", 1, "2026-10-01", "Sale 1", OWNER_USER_ID);
            customerLedgerRepository.insertEntry(conn, retailCustomer.id(), "CREDIT_SALE", 200000, "SALE", 2, "2026-10-02", "Sale 2", OWNER_USER_ID);
        }

        List<CustomerDuesSummary> list = creditLedgerService.getAllCustomersWithDues();
        assertEquals(2, list.size());
        assertEquals(technicianCustomer.id(), list.get(0).customerId());
        assertEquals(Money.ofRupees(5000), list.get(0).currentDues());
        assertEquals(retailCustomer.id(), list.get(1).customerId());
        assertEquals(Money.ofRupees(2000), list.get(1).currentDues());
    }
}
