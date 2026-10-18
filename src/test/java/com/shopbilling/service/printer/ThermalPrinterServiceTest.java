package com.shopbilling.service.printer;

import com.shopbilling.db.*;
import com.shopbilling.model.*;
import com.shopbilling.service.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ThermalPrinterService Tests (58mm & 80mm ESC/POS Printing)")
class ThermalPrinterServiceTest {

    @TempDir
    Path tempDir;

    private TransactionHelper transactionHelper;
    private SaleRepository saleRepository;
    private PaymentRepository paymentRepository;
    private CustomerRepository customerRepository;
    private ProductVariantRepository variantRepository;
    private ProductRepository productRepository;
    private SettingsRepository settingsRepository;

    private ThermalPrinterService printerService;
    private long testSaleId;

    @BeforeEach
    void setUp() {
        Path dbPath = tempDir.resolve("thermal_test.db");
        DatabaseConfig config = DatabaseConfig.forFile(dbPath);
        ConnectionFactory connectionFactory = new ConnectionFactory(config);
        new FlywayMigrator(connectionFactory).migrate();

        transactionHelper = new TransactionHelper(connectionFactory);
        saleRepository = new SaleRepository();
        paymentRepository = new PaymentRepository();
        customerRepository = new CustomerRepository();
        variantRepository = new ProductVariantRepository();
        productRepository = new ProductRepository();
        settingsRepository = new SettingsRepository();

        printerService = new ThermalPrinterService(
                transactionHelper,
                saleRepository,
                paymentRepository,
                customerRepository,
                variantRepository,
                productRepository,
                settingsRepository
        );

        // Seed test data
        testSaleId = transactionHelper.execute(conn -> {
            UserRepository userRepo = new UserRepository();
            User user = userRepo.insert(conn, new User(
                    null, "admin", "hash", UserRole.OWNER, "Admin Owner", true, null
            ));
            long userId = user.id();

            settingsRepository.setSetting(conn, "SHOP_NAME", "UdhyamSetu Electricals", userId);
            settingsRepository.setSetting(conn, "SHOP_PHONE", "9876543210", userId);
            settingsRepository.setSetting(conn, "SHOP_GSTIN", "07AAAAA0000A1Z5", userId);

            Customer cust = customerRepository.insert(conn, new Customer(
                    null, "Ramesh Patel", "9988776655", CustomerType.RETAIL, "Delhi", null, null, null
            ));

            Brand brand = new BrandRepository().insert(conn, "Havells");
            Category cat = new CategoryRepository().insert(conn, "Switch");
            Product prod = productRepository.insert(conn, new Product(
                    null, brand.id(), cat.id(), "Fabio Modular Switch", "PIECE", 10000L, true, null
            ));
            ProductVariant variant = variantRepository.insert(conn, new ProductVariant(
                    null, prod.id(), "6A 1-Way White", Money.ofRupees(150), Money.ofRupees(120), "BAR-123", true, null
            ));

            Sale sale = new Sale(
                    null,
                    SaleMode.BILL,
                    "2026-27",
                    "FY26-27/0042",
                    42,
                    cust.id(),
                    LocalDate.of(2026, 10, 18),
                    null,
                    Money.ofRupees(240),
                    Money.ofRupees(20),
                    Money.ofRupees(220),
                    userId,
                    List.of(),
                    List.of()
            );
            Sale savedSale = saleRepository.insertSale(conn, sale);

            saleRepository.insertSaleItem(conn, new SaleItem(
                    null,
                    savedSale.id(),
                    variant.id(),
                    Quantity.of(2),
                    Money.ofRupees(120),
                    Money.ofRupees(20),
                    Money.ofRupees(220)
            ));

            paymentRepository.insertPayment(conn, savedSale.id(), new PaymentInput(PaymentMode.CASH, Money.ofRupees(100), null));
            paymentRepository.insertPayment(conn, savedSale.id(), new PaymentInput(PaymentMode.UPI, Money.ofRupees(120), "UPI-98765"));

            return savedSale.id();
        });
    }

    @Test
    @DisplayName("58mm receipt text formats cleanly within 32 chars width")
    void testFormatReceipt58mm() {
        String receipt = printerService.formatReceiptText(testSaleId, ThermalWidth.WIDTH_58MM);
        assertNotNull(receipt);

        String[] lines = receipt.split("\n");
        for (String line : lines) {
            assertTrue(line.length() <= 32, "Line exceeds 32 chars for 58mm: '" + line + "'");
        }

        assertTrue(receipt.contains("UdhyamSetu Electricals"));
        assertTrue(receipt.contains("INVOICE: FY26-27/0042"));
        assertTrue(receipt.contains("Ramesh Patel"));
        assertTrue(receipt.contains("NET TOTAL:"));
        assertTrue(receipt.contains("220.00"));
        assertTrue(receipt.contains("CASH"));
        assertTrue(receipt.contains("UPI"));
        assertTrue(receipt.contains("धन्यवाद"));
    }

    @Test
    @DisplayName("80mm receipt text formats cleanly within 48 chars width")
    void testFormatReceipt80mm() {
        String receipt = printerService.formatReceiptText(testSaleId, ThermalWidth.WIDTH_80MM);
        assertNotNull(receipt);

        String[] lines = receipt.split("\n");
        for (String line : lines) {
            assertTrue(line.length() <= 48, "Line exceeds 48 chars for 80mm: '" + line + "'");
        }

        assertTrue(receipt.contains("UdhyamSetu Electricals"));
        assertTrue(receipt.contains("GSTIN: 07AAAAA0000A1Z5"));
        assertTrue(receipt.contains("Fabio Modular Switch"));
        assertTrue(receipt.contains("220.00"));
        assertTrue(receipt.contains("Thank You! Visit Again"));
    }

    @Test
    @DisplayName("ESC/POS byte stream contains initialisation, content, and paper cut command")
    void testGenerateEscPosBytes() {
        byte[] bytes = printerService.generateEscPosBytes(testSaleId, ThermalWidth.WIDTH_80MM);
        assertNotNull(bytes);
        assertTrue(bytes.length > 50, "ESC/POS byte stream too small");

        // Verify ESC @ (0x1B, 0x40) init command at the beginning
        assertEquals(0x1B, bytes[0]);
        assertEquals(0x40, bytes[1]);

        // Verify GS V m (0x1D, 0x56) cut command near end
        boolean hasCut = false;
        for (int i = 0; i < bytes.length - 3; i++) {
            if (bytes[i] == 0x1D && bytes[i + 1] == 0x56) {
                hasCut = true;
                break;
            }
        }
        assertTrue(hasCut, "ESC/POS byte stream must include paper cut sequence");
    }

    @Test
    @DisplayName("Throws EntityNotFoundException for non-existent sale ID")
    void testSaleNotFound() {
        assertThrows(EntityNotFoundException.class, () ->
                printerService.formatReceiptText(99999L, ThermalWidth.WIDTH_58MM)
        );
    }
}
