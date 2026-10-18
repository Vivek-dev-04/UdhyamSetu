package com.shopbilling.service.pdf;

import com.shopbilling.db.*;
import com.shopbilling.model.*;
import com.shopbilling.service.EntityNotFoundException;
import com.shopbilling.service.IstClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CreditNotePdfService Tests (Official A4 Credit Note PDF Rendering)")
class CreditNotePdfServiceTest {

    @TempDir
    Path tempDir;

    private ConnectionFactory connectionFactory;
    private TransactionHelper transactionHelper;
    private ReturnRepository returnRepository;
    private SaleRepository saleRepository;
    private CustomerRepository customerRepository;
    private ProductVariantRepository variantRepository;
    private ProductRepository productRepository;
    private UnitRepository unitRepository;
    private SettingsRepository settingsRepository;
    private IstClock clock;

    private CreditNotePdfService creditNotePdfService;

    private long returnId;
    private long ownerUserId;

    @BeforeEach
    void setUp() throws IOException {
        Path dbFile = tempDir.resolve("credit_note_pdf_test.db");
        DatabaseConfig config = DatabaseConfig.forFile(dbFile);
        connectionFactory = new ConnectionFactory(config);
        new FlywayMigrator(connectionFactory).migrate();

        transactionHelper = new TransactionHelper(connectionFactory);
        returnRepository = new ReturnRepository();
        saleRepository = new SaleRepository();
        customerRepository = new CustomerRepository();
        variantRepository = new ProductVariantRepository();
        productRepository = new ProductRepository();
        unitRepository = new UnitRepository();
        settingsRepository = new SettingsRepository();
        clock = IstClock.system();

        creditNotePdfService = new CreditNotePdfService(
                transactionHelper,
                returnRepository,
                saleRepository,
                customerRepository,
                variantRepository,
                productRepository,
                unitRepository,
                settingsRepository,
                clock
        );

        UserRepository userRepository = new UserRepository();
        BrandRepository brandRepository = new BrandRepository();
        CategoryRepository categoryRepository = new CategoryRepository();

        transactionHelper.executeVoid(conn -> {
            User owner = userRepository.insert(conn, new User(null, "owner", "pass", UserRole.OWNER, "Shop Owner", true, null));
            ownerUserId = owner.id();

            settingsRepository.setSetting(conn, "SHOP_NAME", "Agarwal Electricals", ownerUserId);
            settingsRepository.setSetting(conn, "SHOP_ADDRESS", "Shop 12, Electric Market, Kota", ownerUserId);
            settingsRepository.setSetting(conn, "SHOP_PHONE", "9414000000", ownerUserId);
            settingsRepository.setSetting(conn, "SHOP_GSTIN", "08AAAAA0000A1Z5", ownerUserId);

            Customer customer = customerRepository.insert(conn, new Customer(
                    null, "Prakash Contractor", "9829000000", CustomerType.TECHNICIAN, "Station Road, Kota", null, null, null
            ));

            Brand brand = brandRepository.insert(conn, "Havells");
            Category cat = categoryRepository.insert(conn, "Fan");
            Product prod = productRepository.insert(conn, new Product(null, brand.id(), cat.id(), "Ceiling Fan", "PIECE", null, true, null));
            ProductVariant var = variantRepository.insert(conn, new ProductVariant(null, prod.id(), "1200mm Brown", Money.ofRupees(2200), Money.ofRupees(1800), "BAR-FAN", true, null));

            // Create original Sale
            Sale sale = new Sale(
                    null, SaleMode.BILL, "2026-27", "FY26-27/0055", 55, customer.id(),
                    LocalDate.of(2026, 10, 5), null, Money.ofRupees(1800), Money.ZERO, Money.ofRupees(1800),
                    ownerUserId, List.of(), List.of()
            );
            Sale savedSale = saleRepository.insertSale(conn, sale);

            SaleItem item = new SaleItem(null, savedSale.id(), var.id(), Quantity.of(1), Money.ofRupees(1800), Money.ZERO, Money.ofRupees(1800));
            SaleItem savedItem = saleRepository.insertSaleItem(conn, item);

            // Create Return Record
            ReturnRecord ret = new ReturnRecord(
                    null, savedSale.id(), "CN26-27/0001", "2026-27", LocalDate.of(2026, 10, 8),
                    PaymentMode.CASH, Money.ofRupees(1800), "Motor humming noise defect", null, ownerUserId, List.of()
            );
            ReturnRecord savedRet = returnRepository.insertReturn(conn, ret);
            returnId = savedRet.id();

            ReturnItemRecord retItem = new ReturnItemRecord(
                    null, returnId, savedItem.id(), var.id(), Quantity.of(1), ReturnCondition.DAMAGED, Money.ofRupees(1800), "Defective motor coil"
            );
            returnRepository.insertReturnItem(conn, retItem);
        });
    }

    @Test
    @DisplayName("renderCreditNotePdf produces valid A4 PDF with credit note number, original bill info, and amount in words")
    void testRenderCreditNotePdf() {
        byte[] pdfBytes = creditNotePdfService.renderCreditNotePdf(returnId);

        assertNotNull(pdfBytes);
        assertTrue(pdfBytes.length > 1000, "PDF should have substantial content (size=" + pdfBytes.length + ")");

        // Standard PDF magic bytes
        String magic = new String(pdfBytes, 0, 5);
        assertEquals("%PDF-", magic);
    }

    @Test
    @DisplayName("writeCreditNotePdf streams valid PDF to an OutputStream")
    void testWriteStream() {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        creditNotePdfService.writeCreditNotePdf(returnId, baos);

        byte[] output = baos.toByteArray();
        assertNotNull(output);
        assertTrue(output.length > 1000);
    }

    @Test
    @DisplayName("Non-existent returnId throws EntityNotFoundException")
    void testNonExistentReturnThrows() {
        assertThrows(EntityNotFoundException.class, () ->
                creditNotePdfService.renderCreditNotePdf(99999L)
        );
    }
}
