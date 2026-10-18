package com.shopbilling.service;

import com.shopbilling.db.*;
import com.shopbilling.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ConvertRegisteredSaleService Tests (Converting Unbilled Counter Sales to Billed Invoices)")
class ConvertRegisteredSaleServiceTest {

    @TempDir
    Path tempDir;

    private ConnectionFactory connectionFactory;
    private TransactionHelper transactionHelper;
    private SaleRepository saleRepository;
    private PaymentRepository paymentRepository;
    private BillCounterRepository billCounterRepository;
    private BillCounterService billCounterService;
    private UserRepository userRepository;
    private AuditLogRepository auditLogRepository;
    private AuditLogService auditLogService;
    private IstClock clock;

    private ConvertRegisteredSaleService convertSaleService;
    private RegisterSaleService registerSaleService;

    private long ownerUserId;
    private long staffUserId;
    private long variantId;

    @BeforeEach
    void setUp() throws IOException {
        Path dbFile = tempDir.resolve("convert_sale_test.db");
        DatabaseConfig config = DatabaseConfig.forFile(dbFile);
        connectionFactory = new ConnectionFactory(config);
        new FlywayMigrator(connectionFactory).migrate();

        transactionHelper = new TransactionHelper(connectionFactory);
        saleRepository = new SaleRepository();
        paymentRepository = new PaymentRepository();
        billCounterRepository = new BillCounterRepository();
        billCounterService = new BillCounterService(transactionHelper, billCounterRepository);
        userRepository = new UserRepository();
        auditLogRepository = new AuditLogRepository();
        auditLogService = new AuditLogService(transactionHelper, auditLogRepository, userRepository);
        clock = IstClock.system();

        convertSaleService = new ConvertRegisteredSaleService(
                transactionHelper,
                saleRepository,
                paymentRepository,
                billCounterService,
                userRepository,
                auditLogService,
                clock
        );

        StockRepository stockRepository = new StockRepository();
        SettingsRepository settingsRepository = new SettingsRepository();
        ProductVariantRepository variantRepository = new ProductVariantRepository();
        StockService stockService = new StockService(transactionHelper, stockRepository, settingsRepository, variantRepository);

        CustomerRepository customerRepository = new CustomerRepository();
        CustomerLedgerRepository customerLedgerRepository = new CustomerLedgerRepository();
        PaymentValidationService paymentValidationService = new PaymentValidationService(transactionHelper, customerRepository);
        ProductRepository productRepository = new ProductRepository();
        UnitRepository unitRepository = new UnitRepository();

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

        BrandRepository brandRepository = new BrandRepository();
        CategoryRepository categoryRepository = new CategoryRepository();

        ownerUserId = transactionHelper.execute(conn -> {
            User owner = userRepository.insert(conn, new User(
                    null, "owner", "hashed", UserRole.OWNER, "Shop Owner", true, null
            ));
            User staff = userRepository.insert(conn, new User(
                    null, "staff", "hashed", UserRole.STAFF, "Counter Staff", true, null
            ));
            staffUserId = staff.id();

            Brand brand = brandRepository.insert(conn, "Anchor");
            Category cat = categoryRepository.insert(conn, "Switches");
            Product prod = productRepository.insert(conn, new Product(null, brand.id(), cat.id(), "Roma Switch", "PIECE", 10L, true, null));
            ProductVariant var = variantRepository.insert(conn, new ProductVariant(null, prod.id(), "White 6A", Money.ofRupees(50), Money.ofRupees(45), "BAR123", true, null));
            variantId = var.id();

            // Seed initial stock
            stockService.recordMovementInTransaction(conn, variantId, StockMovementType.PURCHASE, Quantity.of(100), "PURCHASE", 1L, "Initial stock", owner.id());

            return owner.id();
        });
    }

    @Test
    @DisplayName("convertRegisteredSaleToBill successfully assigns gap-free invoice number and writes audit log")
    void testConvertRegisteredSaleSuccess() {
        // 1. Create a registered counter sale
        SaleItemInput item = new SaleItemInput(variantId, Quantity.of(2), Money.ofRupees(45), Money.ZERO);
        PaymentSplit payments = PaymentSplit.single(PaymentMode.CASH, Money.ofRupees(90));

        Sale registeredSale = registerSaleService.registerSale(
                null,
                clock.today(),
                List.of(item),
                Money.ZERO,
                payments,
                ownerUserId
        );

        assertNotNull(registeredSale);
        assertEquals(SaleMode.REGISTERED, registeredSale.saleMode());
        assertNull(registeredSale.billNumber());
        assertFalse(registeredSale.isBilled());

        // 2. Convert to bill
        Sale billedSale = convertSaleService.convertRegisteredSaleToBill(ownerUserId, registeredSale.id());

        assertNotNull(billedSale);
        assertEquals(registeredSale.id(), billedSale.id());
        assertEquals(SaleMode.BILL, billedSale.saleMode());
        assertTrue(billedSale.isBilled());
        assertNotNull(billedSale.billNumber());
        assertEquals(1, billedSale.billSequence());
        assertEquals(Money.ofRupees(90), billedSale.totalAmount());
        assertEquals(1, billedSale.items().size());
        assertEquals(1, billedSale.payments().size());

        // Verify retrieval from DB
        Sale retrieved = transactionHelper.executeRead(conn -> saleRepository.findById(conn, registeredSale.id())).orElseThrow();
        assertEquals(SaleMode.BILL, retrieved.saleMode());
        assertEquals(billedSale.billNumber(), retrieved.billNumber());

        // Verify audit log (FR-44)
        List<AuditLogRecord> logs = auditLogService.getRecentLogs(ownerUserId, 10);
        assertTrue(logs.stream().anyMatch(l ->
                "CONVERT_REGISTERED_TO_BILL".equals(l.action()) &&
                        String.valueOf(registeredSale.id()).equals(l.entityId()) &&
                        "REGISTERED".equals(l.oldValue()) &&
                        billedSale.billNumber().equals(l.newValue())
        ));
    }

    @Test
    @DisplayName("Staff user cannot convert registered sales to bills (Owner only)")
    void testStaffCannotConvert() {
        SaleItemInput item = new SaleItemInput(variantId, Quantity.of(1), Money.ofRupees(45), Money.ZERO);
        PaymentSplit payments = PaymentSplit.single(PaymentMode.CASH, Money.ofRupees(45));

        Sale registeredSale = registerSaleService.registerSale(null, clock.today(), List.of(item), Money.ZERO, payments, staffUserId);

        assertThrows(UnauthorizedException.class, () ->
                convertSaleService.convertRegisteredSaleToBill(staffUserId, registeredSale.id())
        );
    }

    @Test
    @DisplayName("Attempting to convert an already billed sale throws ValidationException")
    void testAlreadyBilledCannotBeConverted() {
        SaleItemInput item = new SaleItemInput(variantId, Quantity.of(1), Money.ofRupees(45), Money.ZERO);
        PaymentSplit payments = PaymentSplit.single(PaymentMode.CASH, Money.ofRupees(45));

        Sale registeredSale = registerSaleService.registerSale(null, clock.today(), List.of(item), Money.ZERO, payments, ownerUserId);
        convertSaleService.convertRegisteredSaleToBill(ownerUserId, registeredSale.id());

        // Second conversion attempt
        assertThrows(ValidationException.class, () ->
                convertSaleService.convertRegisteredSaleToBill(ownerUserId, registeredSale.id())
        );
    }

    @Test
    @DisplayName("Converting non-existent sale throws EntityNotFoundException")
    void testNonExistentSaleThrows() {
        assertThrows(EntityNotFoundException.class, () ->
                convertSaleService.convertRegisteredSaleToBill(ownerUserId, 99999L)
        );
    }
}
