package com.shopbilling.service;

import com.shopbilling.db.BillCounterRepository;
import com.shopbilling.db.BrandRepository;
import com.shopbilling.db.CategoryRepository;
import com.shopbilling.db.ConnectionFactory;
import com.shopbilling.db.CreditNoteCounterRepository;
import com.shopbilling.db.CustomerLedgerRepository;
import com.shopbilling.db.CustomerRepository;
import com.shopbilling.db.DatabaseConfig;
import com.shopbilling.db.FlywayMigrator;
import com.shopbilling.db.PaymentRepository;
import com.shopbilling.db.ProductRepository;
import com.shopbilling.db.ProductVariantRepository;
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
import com.shopbilling.model.Money;
import com.shopbilling.model.PaymentInput;
import com.shopbilling.model.PaymentMode;
import com.shopbilling.model.PaymentSplit;
import com.shopbilling.model.Product;
import com.shopbilling.model.ProductVariant;
import com.shopbilling.model.Quantity;
import com.shopbilling.model.ReturnCondition;
import com.shopbilling.model.ReturnItemInput;
import com.shopbilling.model.ReturnRecord;
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

import static org.junit.jupiter.api.Assertions.*;

class ReturnServiceTest {

    private ConnectionFactory connectionFactory;
    private TransactionHelper transactionHelper;
    private SaleRepository saleRepository;
    private ReturnRepository returnRepository;
    private CreditNoteCounterRepository creditNoteCounterRepository;
    private CustomerLedgerRepository customerLedgerRepository;
    private CustomerRepository customerRepository;
    private StockRepository stockRepository;
    private StockService stockService;
    private SettingsRepository settingsRepository;
    private GenerateBillService generateBillService;
    private RegisterSaleService registerSaleService;
    private ReturnService returnService;

    private Customer retailCustomer;
    private Customer technicianCustomer;
    private ProductVariant switchVariant;
    private ProductVariant wireVariant;

    private static final long OWNER_USER_ID = 1L;
    private static final long STAFF_USER_ID = 2L;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws Exception {
        Path dbPath = tempDir.resolve("return_service_test.db");
        DatabaseConfig config = DatabaseConfig.forFile(dbPath);
        connectionFactory = new ConnectionFactory(config);
        new FlywayMigrator(connectionFactory).migrate();

        transactionHelper = new TransactionHelper(connectionFactory);
        saleRepository = new SaleRepository();
        returnRepository = new ReturnRepository();
        creditNoteCounterRepository = new CreditNoteCounterRepository();
        customerLedgerRepository = new CustomerLedgerRepository();
        customerRepository = new CustomerRepository();
        PaymentRepository paymentRepository = new PaymentRepository();
        ProductVariantRepository variantRepository = new ProductVariantRepository();
        ProductRepository productRepository = new ProductRepository();
        UnitRepository unitRepository = new UnitRepository();
        settingsRepository = new SettingsRepository();
        stockRepository = new StockRepository();
        BillCounterRepository billCounterRepository = new BillCounterRepository();
        BillCounterService billCounterService = new BillCounterService(transactionHelper, billCounterRepository);
        SupplierRepository supplierRepo = new SupplierRepository();
        BrandRepository brandRepo = new BrandRepository();
        CategoryRepository catRepo = new CategoryRepository();

        BrandCategoryService brandCategoryService = new BrandCategoryService(transactionHelper, brandRepo, catRepo);
        ProductService productService = new ProductService(transactionHelper, productRepository, variantRepository, brandRepo, catRepo, unitRepository);
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

        // Seed users: Owner and Staff
        try (Connection conn = connectionFactory.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO users (id, username, password_hash, role, full_name) " +
                    "VALUES (1, 'owner', 'hash1', 'OWNER', 'Shop Owner');");
            stmt.execute("INSERT INTO users (id, username, password_hash, role, full_name) " +
                    "VALUES (2, 'staff', 'hash2', 'STAFF', 'Counter Staff');");
        }

        Brand havells = brandCategoryService.createBrand("Havells");
        Category switches = brandCategoryService.createCategory("Switch");
        Category wires = brandCategoryService.createCategory("Wire");

        Product p1 = productService.createProduct(havells.id(), switches.id(), "Fabio 16A Switch", "PIECE", null);
        switchVariant = productService.addVariant(p1.id(), "White", Money.ofRupees(150), Money.ofRupees(120), "BAR123");

        Product p2 = productService.createProduct(havells.id(), wires.id(), "LifeLine Plus Wire", "METRE", null);
        wireVariant = productService.addVariant(p2.id(), "Red", Money.ofRupees(40), Money.ofRupees(35), "BAR456");

        retailCustomer = customerSupplierService.createCustomer("Ramesh Patel", "9898000000", CustomerType.RETAIL, null, null, null);
        technicianCustomer = customerSupplierService.createCustomer("Sharma Electricals", "9876500000", CustomerType.TECHNICIAN, "Main Bazar", Money.ofRupees(5000), "Key contractor");

        stockService.recordMovement(switchVariant.id(), StockMovementType.PURCHASE,
                Quantity.of(50), "PURCHASE", 1, "Opening Stock", OWNER_USER_ID);
        stockService.recordMovement(wireVariant.id(), StockMovementType.PURCHASE,
                Quantity.of(200), "PURCHASE", 1, "Opening Stock", OWNER_USER_ID);
    }

    @Test
    @DisplayName("Return on billed sale: generates Credit Note and restores SELLABLE stock")
    void shouldProcessReturnOnBilledSaleWithCreditNote() {
        // Bill 10 switches @ 120 = 1200 (Stock drops from 50 to 40)
        SaleItemInput item = SaleItemInput.of(switchVariant.id(), Quantity.of(10), Money.ofRupees(120));
        PaymentSplit payments = PaymentSplit.single(PaymentMode.CASH, Money.ofRupees(1200));

        Sale bill = generateBillService.generateBill(
                retailCustomer.id(),
                LocalDate.of(2026, 10, 18),
                List.of(item),
                Money.ZERO,
                payments,
                OWNER_USER_ID
        );
        assertEquals(Quantity.of(40), stockService.getCurrentStock(switchVariant.id()));

        // Return 2 switches as SELLABLE
        SaleItem saleItem = bill.items().get(0);
        ReturnItemInput returnItem = ReturnItemInput.of(saleItem.id(), Quantity.of(2), ReturnCondition.SELLABLE, "Customer purchased extra");

        ReturnRecord returnRecord = returnService.processReturn(
                bill.id(),
                LocalDate.of(2026, 10, 18),
                List.of(returnItem),
                PaymentMode.CASH,
                "Return against bill " + bill.billNumber(),
                OWNER_USER_ID
        );

        assertNotNull(returnRecord.id());
        assertEquals("CN26-27/0001", returnRecord.creditNoteNumber());
        assertEquals(Money.ofRupees(240), returnRecord.totalRefund());
        assertEquals(1, returnRecord.items().size());

        // Stock must be restored: 40 + 2 = 42
        assertEquals(Quantity.of(42), stockService.getCurrentStock(switchVariant.id()));
    }

    @Test
    @DisplayName("Return on registered sale: does not generate Credit Note")
    void shouldProcessReturnOnRegisteredSaleWithoutCreditNote() {
        SaleItemInput item = SaleItemInput.of(switchVariant.id(), Quantity.of(4), Money.ofRupees(120));
        PaymentSplit payments = PaymentSplit.single(PaymentMode.CASH, Money.ofRupees(480));

        Sale sale = registerSaleService.registerSale(
                retailCustomer.id(),
                LocalDate.of(2026, 10, 18),
                List.of(item),
                Money.ZERO,
                payments,
                OWNER_USER_ID
        );

        SaleItem saleItem = sale.items().get(0);
        ReturnItemInput returnItem = ReturnItemInput.of(saleItem.id(), Quantity.of(1), ReturnCondition.SELLABLE, "Wrong item");

        ReturnRecord returnRecord = returnService.processReturn(
                sale.id(),
                LocalDate.of(2026, 10, 18),
                List.of(returnItem),
                PaymentMode.CASH,
                "Registered sale return",
                OWNER_USER_ID
        );

        assertNotNull(returnRecord.id());
        assertNull(returnRecord.creditNoteNumber(), "Registered sale return must NOT have a credit note number");
        assertEquals(Money.ofRupees(120), returnRecord.totalRefund());
    }

    @Test
    @DisplayName("Return with customer credit refund: reduces customer dues in ledger")
    void shouldProcessReturnWithCustomerCreditAndReduceDues() throws Exception {
        // Technician buys 10 switches on credit -> dues: 1200
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

        try (Connection conn = connectionFactory.getConnection()) {
            assertEquals(120000, customerLedgerRepository.getCurrentDuesPaise(conn, technicianCustomer.id()));
        }

        // Return 3 switches with refund mode CREDIT
        SaleItem saleItem = bill.items().get(0);
        ReturnItemInput returnItem = ReturnItemInput.of(saleItem.id(), Quantity.of(3), ReturnCondition.SELLABLE, "Project cancelled");

        returnService.processReturn(
                bill.id(),
                LocalDate.of(2026, 10, 18),
                List.of(returnItem),
                PaymentMode.CREDIT,
                "Credit ledger adjustment",
                OWNER_USER_ID
        );

        // Dues should be reduced by 360 (1200 - 360 = 840)
        try (Connection conn = connectionFactory.getConnection()) {
            assertEquals(84000, customerLedgerRepository.getCurrentDuesPaise(conn, technicianCustomer.id()));
        }
    }

    @Test
    @DisplayName("Damaged return: does not add back to sellable stock")
    void shouldTrackDamagedReturnWithoutAddingToSellableStock() {
        SaleItemInput item = SaleItemInput.of(switchVariant.id(), Quantity.of(5), Money.ofRupees(120));
        PaymentSplit payments = PaymentSplit.single(PaymentMode.CASH, Money.ofRupees(600));

        Sale bill = generateBillService.generateBill(
                retailCustomer.id(),
                LocalDate.of(2026, 10, 18),
                List.of(item),
                Money.ZERO,
                payments,
                OWNER_USER_ID
        );
        assertEquals(Quantity.of(45), stockService.getCurrentStock(switchVariant.id()));

        // Return 2 damaged switches
        SaleItem saleItem = bill.items().get(0);
        ReturnItemInput returnItem = ReturnItemInput.of(saleItem.id(), Quantity.of(2), ReturnCondition.DAMAGED, "Cracked plastic casing");

        ReturnRecord returnRecord = returnService.processReturn(
                bill.id(),
                LocalDate.of(2026, 10, 18),
                List.of(returnItem),
                PaymentMode.CASH,
                "Damaged return",
                OWNER_USER_ID
        );

        assertNotNull(returnRecord.id());
        // Sellable stock remains 45 (damaged items do NOT increase sellable stock)
        assertEquals(Quantity.of(45), stockService.getCurrentStock(switchVariant.id()));
    }

    @Test
    @DisplayName("Should prevent returning more than originally sold quantity")
    void shouldPreventReturningMoreThanSoldQuantity() {
        SaleItemInput item = SaleItemInput.of(switchVariant.id(), Quantity.of(5), Money.ofRupees(120));
        PaymentSplit payments = PaymentSplit.single(PaymentMode.CASH, Money.ofRupees(600));

        Sale bill = generateBillService.generateBill(
                retailCustomer.id(),
                LocalDate.of(2026, 10, 18),
                List.of(item),
                Money.ZERO,
                payments,
                OWNER_USER_ID
        );
        SaleItem saleItem = bill.items().get(0);

        // 1. Trying to return 6 switches when only 5 were bought -> fails
        ReturnItemInput invalidReturn = ReturnItemInput.of(saleItem.id(), Quantity.of(6), ReturnCondition.SELLABLE, "Excess return");
        ValidationException ex = assertThrows(ValidationException.class, () ->
                returnService.processReturn(bill.id(), null, List.of(invalidReturn), PaymentMode.CASH, "Fail", OWNER_USER_ID)
        );
        assertTrue(ex.getMessage().contains("Max available: 5"));

        // 2. Returning 3 switches -> succeeds
        ReturnItemInput return1 = ReturnItemInput.of(saleItem.id(), Quantity.of(3), ReturnCondition.SELLABLE, "First return");
        returnService.processReturn(bill.id(), null, List.of(return1), PaymentMode.CASH, "OK", OWNER_USER_ID);

        // 3. Trying to return 3 more switches (total 6 > 5) -> fails
        ReturnItemInput return2 = ReturnItemInput.of(saleItem.id(), Quantity.of(3), ReturnCondition.SELLABLE, "Second return");
        ValidationException ex2 = assertThrows(ValidationException.class, () ->
                returnService.processReturn(bill.id(), null, List.of(return2), PaymentMode.CASH, "Fail", OWNER_USER_ID)
        );
        assertTrue(ex2.getMessage().contains("Already returned: 3, Max available: 2"));
    }

    @Test
    @DisplayName("Staff return approval limit: rejects above ₹500, allows below ₹500")
    void shouldEnforceStaffReturnApprovalLimit() {
        SaleItemInput item = SaleItemInput.of(switchVariant.id(), Quantity.of(10), Money.ofRupees(120));
        PaymentSplit payments = PaymentSplit.single(PaymentMode.CASH, Money.ofRupees(1200));

        Sale bill = generateBillService.generateBill(
                retailCustomer.id(),
                null,
                List.of(item),
                Money.ZERO,
                payments,
                OWNER_USER_ID
        );
        SaleItem saleItem = bill.items().get(0);

        // Staff tries to return 5 switches = ₹600 (> ₹500 limit) -> rejected
        ReturnItemInput highReturn = ReturnItemInput.of(saleItem.id(), Quantity.of(5), ReturnCondition.SELLABLE, "Too high");
        ValidationException ex = assertThrows(ValidationException.class, () ->
                returnService.processReturn(bill.id(), null, List.of(highReturn), PaymentMode.CASH, "Staff try", STAFF_USER_ID)
        );
        assertTrue(ex.getMessage().contains("Staff return limit exceeded"));

        // Staff returns 3 switches = ₹360 (<= ₹500 limit) -> succeeds
        ReturnItemInput okReturn = ReturnItemInput.of(saleItem.id(), Quantity.of(3), ReturnCondition.SELLABLE, "Within limit");
        ReturnRecord record = returnService.processReturn(bill.id(), null, List.of(okReturn), PaymentMode.CASH, "Staff ok", STAFF_USER_ID);
        assertNotNull(record.id());

        // Owner can return remaining 5 switches = ₹600 without limit
        ReturnItemInput ownerReturn = ReturnItemInput.of(saleItem.id(), Quantity.of(5), ReturnCondition.SELLABLE, "Owner override");
        ReturnRecord ownerRecord = returnService.processReturn(bill.id(), null, List.of(ownerReturn), PaymentMode.CASH, "Owner ok", OWNER_USER_ID);
        assertNotNull(ownerRecord.id());
    }

    @Test
    @DisplayName("Reject credit refund for walk-in customer")
    void shouldRejectCreditRefundForWalkInCustomer() {
        SaleItemInput item = SaleItemInput.of(switchVariant.id(), Quantity.of(2), Money.ofRupees(120));
        PaymentSplit payments = PaymentSplit.single(PaymentMode.CASH, Money.ofRupees(240));

        Sale bill = generateBillService.generateBill(
                null, // Walk-in
                null,
                List.of(item),
                Money.ZERO,
                payments,
                OWNER_USER_ID
        );
        SaleItem saleItem = bill.items().get(0);

        ReturnItemInput returnItem = ReturnItemInput.of(saleItem.id(), Quantity.of(1), ReturnCondition.SELLABLE, "Return");
        ValidationException ex = assertThrows(ValidationException.class, () ->
                returnService.processReturn(bill.id(), null, List.of(returnItem), PaymentMode.CREDIT, "Credit refund", OWNER_USER_ID)
        );
        assertTrue(ex.getMessage().contains("Cannot issue refund as CREDIT for a walk-in sale"));
    }
}
