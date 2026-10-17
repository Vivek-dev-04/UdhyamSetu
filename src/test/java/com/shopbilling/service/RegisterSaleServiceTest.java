package com.shopbilling.service;

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
import com.shopbilling.model.SaleMode;
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

class RegisterSaleServiceTest {

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
    private IstClock clock;
    private RegisterSaleService registerSaleService;

    private Customer retailCustomer;
    private Customer technicianCustomer;
    private ProductVariant switchVariant; // Piece unit (no decimals)
    private ProductVariant wireVariant;   // Metre unit (allows decimals)

    private static final long OWNER_USER_ID = 1L;
    private static final long STAFF_USER_ID = 2L;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws Exception {
        Path dbPath = tempDir.resolve("register_sale_test.db");
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
        SupplierRepository supplierRepo = new SupplierRepository();
        BrandRepository brandRepo = new BrandRepository();
        CategoryRepository catRepo = new CategoryRepository();

        BrandCategoryService brandCategoryService = new BrandCategoryService(transactionHelper, brandRepo, catRepo);
        ProductService productService = new ProductService(transactionHelper, productRepository, variantRepository, brandRepo, catRepo, unitRepository);
        stockService = new StockService(transactionHelper, stockRepository, settingsRepository, variantRepository);
        CustomerSupplierService customerSupplierService = new CustomerSupplierService(transactionHelper, customerRepository, supplierRepo);
        paymentValidationService = new PaymentValidationService(transactionHelper, customerRepository);
        clock = IstClock.system();

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
    @DisplayName("Should register a sale with single cash payment and deduct stock without bill number")
    void shouldRegisterSaleWithCash() throws Exception {
        // 5 switches @ Rs 120 = Rs 600
        SaleItemInput item = SaleItemInput.of(switchVariant.id(), Quantity.of(5), Money.ofRupees(120));
        PaymentSplit payments = PaymentSplit.single(PaymentMode.CASH, Money.ofRupees(600));

        Sale sale = registerSaleService.registerSale(
                retailCustomer.id(),
                LocalDate.of(2026, 10, 17),
                List.of(item),
                Money.ZERO,
                payments,
                OWNER_USER_ID
        );

        assertNotNull(sale.id());
        assertEquals(SaleMode.REGISTERED, sale.saleMode());
        assertNull(sale.billNumber(), "Registered sale must NOT have a bill number assigned");
        assertNull(sale.billSequence(), "Registered sale must NOT have a bill sequence");
        assertEquals(Money.ofRupees(600), sale.subtotal());
        assertEquals(Money.ZERO, sale.discount());
        assertEquals(Money.ofRupees(600), sale.totalAmount());
        assertEquals(1, sale.items().size());
        assertEquals(1, sale.payments().size());
        assertEquals(PaymentMode.CASH, sale.payments().get(0).mode());

        // Stock should be deducted by 5 (50 - 5 = 45)
        Quantity remaining = stockService.getCurrentStock(switchVariant.id());
        assertEquals(Quantity.of(45), remaining);
    }

    @Test
    @DisplayName("Should register sale with split payment (UPI + Cash)")
    void shouldRegisterSaleWithSplitPayment() throws Exception {
        // 10m wire @ Rs 35 = Rs 350
        SaleItemInput item = SaleItemInput.of(wireVariant.id(), Quantity.of(10), Money.ofRupees(35));
        PaymentSplit payments = PaymentSplit.of(
                PaymentInput.of(PaymentMode.UPI, Money.ofRupees(200), "UPI-998877"),
                PaymentInput.of(PaymentMode.CASH, Money.ofRupees(150))
        );

        Sale sale = registerSaleService.registerSale(
                null, // Walk-in customer
                null, // Default clock date
                List.of(item),
                Money.ZERO,
                payments,
                OWNER_USER_ID
        );

        assertNotNull(sale.id());
        assertNull(sale.customerId());
        assertEquals(Money.ofRupees(350), sale.totalAmount());
        assertEquals(2, sale.payments().size());

        // Wire stock: 200 - 10 = 190
        Quantity remainingWire = stockService.getCurrentStock(wireVariant.id());
        assertEquals(Quantity.of(190), remainingWire);
    }

    @Test
    @DisplayName("Should register credit sale and update customer ledger dues")
    void shouldRegisterCreditSaleAndUpdateLedger() throws Exception {
        // 20 switches @ Rs 120 = Rs 2,400 on credit
        SaleItemInput item = SaleItemInput.of(switchVariant.id(), Quantity.of(20), Money.ofRupees(120));
        PaymentSplit payments = PaymentSplit.single(PaymentMode.CREDIT, Money.ofRupees(2400));

        Sale sale = registerSaleService.registerSale(
                technicianCustomer.id(),
                LocalDate.of(2026, 10, 17),
                List.of(item),
                Money.ZERO,
                payments,
                OWNER_USER_ID
        );

        assertNotNull(sale.id());

        // Verify customer ledger dues are updated to Rs 2,400
        try (Connection conn = connectionFactory.getConnection()) {
            long currentDuesPaise = customerLedgerRepository.getCurrentDuesPaise(conn, technicianCustomer.id());
            assertEquals(240000, currentDuesPaise);
        }
    }

    @Test
    @DisplayName("Should reject credit sale without customer")
    void shouldRejectCreditSaleWithoutCustomer() {
        SaleItemInput item = SaleItemInput.of(switchVariant.id(), Quantity.of(2), Money.ofRupees(120));
        PaymentSplit payments = PaymentSplit.single(PaymentMode.CREDIT, Money.ofRupees(240));

        ValidationException ex = assertThrows(ValidationException.class, () ->
                registerSaleService.registerSale(
                        null, // Missing customer
                        null,
                        List.of(item),
                        Money.ZERO,
                        payments,
                        OWNER_USER_ID
                )
        );
        assertTrue(ex.getMessage().contains("Credit sale requires an identified customer"));
    }

    @Test
    @DisplayName("Should reject sale when negative stock is disallowed and stock is insufficient")
    void shouldRejectWhenNegativeStockDisallowed() throws Exception {
        // Attempt to sell 60 switches when only 50 in stock
        SaleItemInput item = SaleItemInput.of(switchVariant.id(), Quantity.of(60), Money.ofRupees(120));
        PaymentSplit payments = PaymentSplit.single(PaymentMode.CASH, Money.ofRupees(7200));

        ValidationException ex = assertThrows(ValidationException.class, () ->
                registerSaleService.registerSale(
                        retailCustomer.id(),
                        null,
                        List.of(item),
                        Money.ZERO,
                        payments,
                        OWNER_USER_ID
                )
        );
        assertTrue(ex.getMessage().contains("Negative stock is not permitted"));

        // Atomic rollback check: stock remains 50
        Quantity remaining = stockService.getCurrentStock(switchVariant.id());
        assertEquals(Quantity.of(50), remaining);

        // No sale should exist
        try (Connection conn = connectionFactory.getConnection();
             Statement stmt = conn.createStatement();
             var rs = stmt.executeQuery("SELECT COUNT(*) FROM sales")) {
            rs.next();
            assertEquals(0, rs.getInt(1));
        }
    }

    @Test
    @DisplayName("Should reject fractional quantity on non-decimal unit (Piece)")
    void shouldRejectFractionalQuantityOnPiece() {
        // Switch is Unit PIECE, 1.5 is invalid
        SaleItemInput item = SaleItemInput.of(switchVariant.id(), Quantity.parse("1.5"), Money.ofRupees(120));
        PaymentSplit payments = PaymentSplit.single(PaymentMode.CASH, Money.ofRupees(180));

        ValidationException ex = assertThrows(ValidationException.class, () ->
                registerSaleService.registerSale(
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

    @Test
    @DisplayName("Should allow decimal quantity on metre unit (Wire)")
    void shouldAllowDecimalQuantityOnMetre() throws Exception {
        // Wire is Unit METRE, 12.75 metres is valid
        SaleItemInput item = SaleItemInput.of(wireVariant.id(), Quantity.parse("12.75"), Money.ofRupees(40));
        // 12.75 * 40 = 510
        PaymentSplit payments = PaymentSplit.single(PaymentMode.CASH, Money.ofRupees(510));

        Sale sale = registerSaleService.registerSale(
                retailCustomer.id(),
                null,
                List.of(item),
                Money.ZERO,
                payments,
                OWNER_USER_ID
        );

        assertNotNull(sale.id());
        assertEquals(Money.ofRupees(510), sale.totalAmount());
    }

    @Test
    @DisplayName("Should enforce staff discount limit (5% maximum)")
    void shouldEnforceStaffDiscountLimit() {
        // 1 switch @ Rs 100 with Rs 10 line discount = 10% discount (> 5% staff limit)
        SaleItemInput item = SaleItemInput.of(switchVariant.id(), Quantity.of(1), Money.ofRupees(100), Money.ofRupees(10));
        PaymentSplit payments = PaymentSplit.single(PaymentMode.CASH, Money.ofRupees(90));

        // Staff user id 2 attempts 10% discount
        ValidationException ex = assertThrows(ValidationException.class, () ->
                registerSaleService.registerSale(
                        retailCustomer.id(),
                        null,
                        List.of(item),
                        Money.ZERO,
                        payments,
                        STAFF_USER_ID
                )
        );
        assertTrue(ex.getMessage().contains("Staff discount limit exceeded"));

        // Owner user id 1 can give 10% discount without restriction
        assertDoesNotThrow(() ->
                registerSaleService.registerSale(
                        retailCustomer.id(),
                        null,
                        List.of(item),
                        Money.ZERO,
                        payments,
                        OWNER_USER_ID
                )
        );
    }

    @Test
    @DisplayName("Should reject staff registering sale if STAFF_CAN_REGISTER_SALE is false")
    void shouldBlockStaffRegistrationIfDisabled() {
        try (Connection conn = connectionFactory.getConnection()) {
            settingsRepository.setSetting(conn, "STAFF_CAN_REGISTER_SALE", "false", OWNER_USER_ID);
        } catch (Exception e) {
            fail("Failed to update setting: " + e.getMessage());
        }

        SaleItemInput item = SaleItemInput.of(switchVariant.id(), Quantity.of(1), Money.ofRupees(120));
        PaymentSplit payments = PaymentSplit.single(PaymentMode.CASH, Money.ofRupees(120));

        ValidationException ex = assertThrows(ValidationException.class, () ->
                registerSaleService.registerSale(
                        retailCustomer.id(),
                        null,
                        List.of(item),
                        Money.ZERO,
                        payments,
                        STAFF_USER_ID
                )
        );
        assertTrue(ex.getMessage().contains("Counter staff members are not permitted to register unbilled sales"));
    }
}
