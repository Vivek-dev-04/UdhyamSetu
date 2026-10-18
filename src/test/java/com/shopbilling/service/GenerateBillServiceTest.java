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
import com.shopbilling.model.PaymentInput;
import com.shopbilling.model.PaymentMode;
import com.shopbilling.model.PaymentSplit;
import com.shopbilling.model.Product;
import com.shopbilling.model.ProductVariant;
import com.shopbilling.model.Quantity;
import com.shopbilling.model.Sale;
import com.shopbilling.model.SaleItemInput;
import com.shopbilling.model.SaleMode;
import com.shopbilling.model.StockMovementType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class GenerateBillServiceTest {

    private ConnectionFactory connectionFactory;
    private TransactionHelper transactionHelper;
    private SaleRepository saleRepository;
    private PaymentRepository paymentRepository;
    private PaymentValidationService paymentValidationService;
    private StockRepository stockRepository;
    private StockService stockService;
    private CustomerLedgerRepository customerLedgerRepository;
    private CustomerRepository customerRepository;
    private ProductVariantRepository variantRepository;
    private ProductRepository productRepository;
    private UnitRepository unitRepository;
    private SettingsRepository settingsRepository;
    private BillCounterRepository billCounterRepository;
    private BillCounterService billCounterService;
    private IstClock clock;
    private GenerateBillService generateBillService;
    private RegisterSaleService registerSaleService;

    private Customer retailCustomer;
    private Customer technicianCustomer;
    private ProductVariant switchVariant; // Piece unit (no decimals)
    private ProductVariant wireVariant;   // Metre unit (allows decimals)

    private static final long OWNER_USER_ID = 1L;
    private static final long STAFF_USER_ID = 2L;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws Exception {
        Path dbPath = tempDir.resolve("generate_bill_test.db");
        DatabaseConfig config = DatabaseConfig.forFile(dbPath);
        connectionFactory = new ConnectionFactory(config);
        new FlywayMigrator(connectionFactory).migrate();

        transactionHelper = new TransactionHelper(connectionFactory);
        saleRepository = new SaleRepository();
        paymentRepository = new PaymentRepository();
        customerRepository = new CustomerRepository();
        customerLedgerRepository = new CustomerLedgerRepository();
        variantRepository = new ProductVariantRepository();
        productRepository = new ProductRepository();
        unitRepository = new UnitRepository();
        settingsRepository = new SettingsRepository();
        stockRepository = new StockRepository();
        billCounterRepository = new BillCounterRepository();
        billCounterService = new BillCounterService(transactionHelper, billCounterRepository);
        SupplierRepository supplierRepo = new SupplierRepository();
        BrandRepository brandRepo = new BrandRepository();
        CategoryRepository catRepo = new CategoryRepository();

        BrandCategoryService brandCategoryService = new BrandCategoryService(transactionHelper, brandRepo, catRepo);
        ProductService productService = new ProductService(transactionHelper, productRepository, variantRepository, brandRepo, catRepo, unitRepository);
        stockService = new StockService(transactionHelper, stockRepository, settingsRepository, variantRepository);
        CustomerSupplierService customerSupplierService = new CustomerSupplierService(transactionHelper, customerRepository, supplierRepo);
        paymentValidationService = new PaymentValidationService(transactionHelper, customerRepository);
        clock = IstClock.system();

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

        // Seed users: Owner and Staff
        try (Connection conn = connectionFactory.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO users (id, username, password_hash, role, full_name) " +
                    "VALUES (1, 'owner', 'hash1', 'OWNER', 'Shop Owner');");
            stmt.execute("INSERT INTO users (id, username, password_hash, role, full_name) " +
                    "VALUES (2, 'staff', 'hash2', 'STAFF', 'Counter Staff');");
        }

        // Seed master catalogue data via services
        Brand havells = brandCategoryService.createBrand("Havells");
        Category switches = brandCategoryService.createCategory("Switch");
        Category wires = brandCategoryService.createCategory("Wire");

        Product p1 = productService.createProduct(havells.id(), switches.id(), "Fabio 16A Switch", "PIECE", null);
        switchVariant = productService.addVariant(p1.id(), "White", Money.ofRupees(150), Money.ofRupees(120), "BAR123");

        Product p2 = productService.createProduct(havells.id(), wires.id(), "LifeLine Plus Wire", "METRE", null);
        wireVariant = productService.addVariant(p2.id(), "Red", Money.ofRupees(40), Money.ofRupees(35), "BAR456");

        // Seed customers
        retailCustomer = customerSupplierService.createCustomer("Ramesh Patel", "9898000000", CustomerType.RETAIL, null, null, null);
        technicianCustomer = customerSupplierService.createCustomer("Sharma Electricals", "9876500000", CustomerType.TECHNICIAN, "Main Bazar", Money.ofRupees(5000), "Key contractor");

        // Seed initial stock: 50 switches and 200m wire
        stockService.recordMovement(switchVariant.id(), StockMovementType.PURCHASE,
                Quantity.of(50), "PURCHASE", 1, "Opening Stock", OWNER_USER_ID);
        stockService.recordMovement(wireVariant.id(), StockMovementType.PURCHASE,
                Quantity.of(200), "PURCHASE", 1, "Opening Stock", OWNER_USER_ID);
    }

    @Test
    @DisplayName("Should generate bill with cash payment and increment sequential bill counter")
    void shouldGenerateBillWithCashAndIncrementBillCounter() {
        SaleItemInput item = SaleItemInput.of(switchVariant.id(), Quantity.of(5), Money.ofRupees(120));
        PaymentSplit payments = PaymentSplit.single(PaymentMode.CASH, Money.ofRupees(600));

        LocalDate billDate = LocalDate.of(2026, 10, 18);
        Sale bill1 = generateBillService.generateBill(
                retailCustomer.id(),
                billDate,
                List.of(item),
                Money.ZERO,
                payments,
                OWNER_USER_ID
        );

        assertNotNull(bill1.id());
        assertEquals(SaleMode.BILL, bill1.saleMode());
        assertTrue(bill1.isBilled());
        assertFalse(bill1.isRegistered());
        assertEquals("FY26-27/0001", bill1.billNumber());
        assertEquals(1, bill1.billSequence());
        assertEquals("2026-27", bill1.financialYear());
        assertEquals(Money.ofRupees(600), bill1.totalAmount());
        assertEquals(1, bill1.items().size());
        assertEquals(1, bill1.payments().size());

        // Stock deducted by 5 (50 - 5 = 45)
        Quantity stock = stockService.getCurrentStock(switchVariant.id());
        assertEquals(Quantity.of(45), stock);

        // Generate second bill - should get sequence 2
        Sale bill2 = generateBillService.generateBill(
                retailCustomer.id(),
                billDate,
                List.of(item),
                Money.ZERO,
                payments,
                OWNER_USER_ID
        );
        assertEquals("FY26-27/0002", bill2.billNumber());
        assertEquals(2, bill2.billSequence());
        assertEquals(Quantity.of(40), stockService.getCurrentStock(switchVariant.id()));
    }

    @Test
    @DisplayName("Should generate bill with split payment (UPI + Cash) for walk-in customer")
    void shouldGenerateBillWithSplitPaymentAndWalkInCustomer() {
        SaleItemInput item = SaleItemInput.of(wireVariant.id(), Quantity.of(10), Money.ofRupees(35));
        PaymentSplit payments = PaymentSplit.of(
                PaymentInput.of(PaymentMode.UPI, Money.ofRupees(200), "UPI-TXN-1122"),
                PaymentInput.of(PaymentMode.CASH, Money.ofRupees(150))
        );

        Sale bill = generateBillService.generateBill(
                null,
                null,
                List.of(item),
                Money.ZERO,
                payments,
                OWNER_USER_ID
        );

        assertNotNull(bill.id());
        assertNull(bill.customerId());
        assertEquals("FY26-27/0001", bill.billNumber());
        assertEquals(Money.ofRupees(350), bill.totalAmount());
        assertEquals(2, bill.payments().size());
        assertEquals(Quantity.of(190), stockService.getCurrentStock(wireVariant.id()));
    }

    @Test
    @DisplayName("Should generate credit bill and update customer ledger dues")
    void shouldGenerateCreditBillAndUpdateCustomerLedger() throws Exception {
        SaleItemInput item = SaleItemInput.of(switchVariant.id(), Quantity.of(10), Money.ofRupees(120));
        PaymentSplit payments = PaymentSplit.single(PaymentMode.CREDIT, Money.ofRupees(1200));

        Sale bill = generateBillService.generateBill(
                technicianCustomer.id(),
                LocalDate.of(2026, 10, 18),
                List.of(item),
                Money.ZERO,
                payments,
                OWNER_USER_ID
        );

        assertNotNull(bill.id());
        assertEquals("FY26-27/0001", bill.billNumber());

        try (Connection conn = connectionFactory.getConnection()) {
            long currentDuesPaise = customerLedgerRepository.getCurrentDuesPaise(conn, technicianCustomer.id());
            assertEquals(120000, currentDuesPaise);
        }
    }

    @Test
    @DisplayName("Staff discount limit: should succeed under 5% and reject over 5%")
    void shouldEnforceStaffDiscountLimit() {
        // Line total: 10 * 120 = 1200. 5% of 1200 = 60.
        // Requested discount 50 (4.16% <= 5%) -> should succeed for STAFF
        SaleItemInput item = SaleItemInput.of(switchVariant.id(), Quantity.of(10), Money.ofRupees(120));
        PaymentSplit okPayments = PaymentSplit.single(PaymentMode.CASH, Money.ofRupees(1150));

        Sale bill = generateBillService.generateBill(
                retailCustomer.id(),
                null,
                List.of(item),
                Money.ofRupees(50),
                okPayments,
                STAFF_USER_ID
        );
        assertNotNull(bill.id());

        // Now staff tries to give 100 discount (8.33% > 5%) -> must throw ValidationException
        PaymentSplit failPayments = PaymentSplit.single(PaymentMode.CASH, Money.ofRupees(1100));
        ValidationException ex = assertThrows(ValidationException.class, () ->
                generateBillService.generateBill(
                        retailCustomer.id(),
                        null,
                        List.of(item),
                        Money.ofRupees(100),
                        failPayments,
                        STAFF_USER_ID
                )
        );
        assertTrue(ex.getMessage().contains("Staff discount limit exceeded"));

        // However, OWNER can give 100 discount without limit
        Sale ownerBill = generateBillService.generateBill(
                retailCustomer.id(),
                null,
                List.of(item),
                Money.ofRupees(100),
                failPayments,
                OWNER_USER_ID
        );
        assertNotNull(ownerBill.id());
    }

    @Test
    @DisplayName("Should reject negative stock and rollback bill counter")
    void shouldRejectNegativeStockAndRollbackBillCounter() {
        // Current switch stock is 50. Requesting 60 should fail.
        SaleItemInput item = SaleItemInput.of(switchVariant.id(), Quantity.of(60), Money.ofRupees(120));
        PaymentSplit payments = PaymentSplit.single(PaymentMode.CASH, Money.ofRupees(7200));

        ValidationException ex = assertThrows(ValidationException.class, () ->
                generateBillService.generateBill(
                        retailCustomer.id(),
                        null,
                        List.of(item),
                        Money.ZERO,
                        payments,
                        OWNER_USER_ID
                )
        );
        assertTrue(ex.getMessage().contains("Negative stock is not permitted"));

        // Because of atomic rollback, next successful bill must receive sequence 1 (gap-free!)
        SaleItemInput validItem = SaleItemInput.of(switchVariant.id(), Quantity.of(5), Money.ofRupees(120));
        PaymentSplit validPayments = PaymentSplit.single(PaymentMode.CASH, Money.ofRupees(600));

        Sale validBill = generateBillService.generateBill(
                retailCustomer.id(),
                null,
                List.of(validItem),
                Money.ZERO,
                validPayments,
                OWNER_USER_ID
        );
        assertEquals("FY26-27/0001", validBill.billNumber());
        assertEquals(1, validBill.billSequence());
    }

    @Test
    @DisplayName("Owner can convert existing registered sale to official bill without re-deducting stock")
    void shouldConvertRegisteredSaleToBillByOwner() {
        // 1. Register unbilled sale: 10 switches (stock drops from 50 to 40)
        SaleItemInput item = SaleItemInput.of(switchVariant.id(), Quantity.of(10), Money.ofRupees(120));
        PaymentSplit payments = PaymentSplit.single(PaymentMode.CASH, Money.ofRupees(1200));

        Sale regSale = registerSaleService.registerSale(
                retailCustomer.id(),
                LocalDate.of(2026, 10, 18),
                List.of(item),
                Money.ZERO,
                payments,
                STAFF_USER_ID
        );
        assertEquals(SaleMode.REGISTERED, regSale.saleMode());
        assertNull(regSale.billNumber());
        assertEquals(Quantity.of(40), stockService.getCurrentStock(switchVariant.id()));

        // 2. Owner converts the registered sale to an official bill
        Sale billedSale = generateBillService.convertToBill(regSale.id(), OWNER_USER_ID);

        assertEquals(regSale.id(), billedSale.id());
        assertEquals(SaleMode.BILL, billedSale.saleMode());
        assertEquals("FY26-27/0001", billedSale.billNumber());
        assertEquals(1, billedSale.billSequence());
        assertEquals(1, billedSale.items().size());
        assertEquals(1, billedSale.payments().size());

        // Stock MUST NOT be deducted again (still 40!)
        assertEquals(Quantity.of(40), stockService.getCurrentStock(switchVariant.id()));
    }

    @Test
    @DisplayName("Staff cannot convert registered sale to bill")
    void shouldPreventStaffFromConvertingRegisteredSaleToBill() {
        SaleItemInput item = SaleItemInput.of(switchVariant.id(), Quantity.of(2), Money.ofRupees(120));
        PaymentSplit payments = PaymentSplit.single(PaymentMode.CASH, Money.ofRupees(240));

        Sale regSale = registerSaleService.registerSale(
                retailCustomer.id(),
                LocalDate.of(2026, 10, 18),
                List.of(item),
                Money.ZERO,
                payments,
                OWNER_USER_ID
        );

        ValidationException ex = assertThrows(ValidationException.class, () ->
                generateBillService.convertToBill(regSale.id(), STAFF_USER_ID)
        );
        assertTrue(ex.getMessage().contains("Only the shop owner is authorized"));
    }

    @Test
    @DisplayName("Cannot convert already billed sale to bill")
    void shouldPreventConvertingAlreadyBilledSale() {
        SaleItemInput item = SaleItemInput.of(switchVariant.id(), Quantity.of(2), Money.ofRupees(120));
        PaymentSplit payments = PaymentSplit.single(PaymentMode.CASH, Money.ofRupees(240));

        Sale bill = generateBillService.generateBill(
                retailCustomer.id(),
                null,
                List.of(item),
                Money.ZERO,
                payments,
                OWNER_USER_ID
        );

        ValidationException ex = assertThrows(ValidationException.class, () ->
                generateBillService.convertToBill(bill.id(), OWNER_USER_ID)
        );
        assertTrue(ex.getMessage().contains("already billed"));
    }

    @Test
    @DisplayName("Should retrieve full sale by bill number and ID")
    void shouldRetrieveBilledSaleByIdAndBillNumber() {
        SaleItemInput item = SaleItemInput.of(switchVariant.id(), Quantity.of(3), Money.ofRupees(120));
        PaymentSplit payments = PaymentSplit.single(PaymentMode.CASH, Money.ofRupees(360));

        Sale bill = generateBillService.generateBill(
                retailCustomer.id(),
                null,
                List.of(item),
                Money.ZERO,
                payments,
                OWNER_USER_ID
        );

        Optional<Sale> byId = generateBillService.getSaleById(bill.id());
        assertTrue(byId.isPresent());
        assertEquals("FY26-27/0001", byId.get().billNumber());
        assertEquals(1, byId.get().items().size());
        assertEquals(1, byId.get().payments().size());

        Optional<Sale> byBillNo = generateBillService.getSaleByBillNumber("FY26-27/0001");
        assertTrue(byBillNo.isPresent());
        assertEquals(bill.id(), byBillNo.get().id());
        assertEquals(Money.ofRupees(360), byBillNo.get().totalAmount());
    }

    @Test
    @DisplayName("Should reject decimal quantity for non-decimal unit")
    void shouldRejectDecimalQuantityForNonDecimalUnit() {
        // Switch is PIECE which allows_decimal = 0. Providing 2.5 must fail.
        SaleItemInput item = SaleItemInput.of(switchVariant.id(), Quantity.of(new BigDecimal("2.5")), Money.ofRupees(120));
        PaymentSplit payments = PaymentSplit.single(PaymentMode.CASH, Money.ofRupees(300));

        ValidationException ex = assertThrows(ValidationException.class, () ->
                generateBillService.generateBill(
                        retailCustomer.id(),
                        null,
                        List.of(item),
                        Money.ZERO,
                        payments,
                        OWNER_USER_ID
                )
        );
        assertTrue(ex.getMessage().contains("does not allow fractional quantities"));
    }
}
