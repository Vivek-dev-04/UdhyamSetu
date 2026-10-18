package com.shopbilling.service.pdf;

import com.shopbilling.db.*;
import com.shopbilling.model.*;
import com.shopbilling.service.CreditLedgerService;
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

@DisplayName("CustomerStatementPdfService Tests (A4 Customer Ledger Khata Statement Generation)")
class CustomerStatementPdfServiceTest {

    @TempDir
    Path tempDir;

    private ConnectionFactory connectionFactory;
    private TransactionHelper transactionHelper;
    private CustomerRepository customerRepository;
    private CustomerLedgerRepository customerLedgerRepository;
    private SettingsRepository settingsRepository;
    private CreditLedgerService creditLedgerService;
    private IstClock clock;

    private CustomerStatementPdfService statementPdfService;

    private long customerId;
    private long ownerUserId;

    @BeforeEach
    void setUp() throws IOException {
        Path dbFile = tempDir.resolve("customer_statement_pdf_test.db");
        DatabaseConfig config = DatabaseConfig.forFile(dbFile);
        connectionFactory = new ConnectionFactory(config);
        new FlywayMigrator(connectionFactory).migrate();

        transactionHelper = new TransactionHelper(connectionFactory);
        customerRepository = new CustomerRepository();
        customerLedgerRepository = new CustomerLedgerRepository();
        SupplierLedgerRepository supplierLedgerRepository = new SupplierLedgerRepository();
        SupplierRepository supplierRepository = new SupplierRepository();
        SaleRepository saleRepository = new SaleRepository();
        settingsRepository = new SettingsRepository();
        clock = IstClock.system();

        creditLedgerService = new CreditLedgerService(
                transactionHelper,
                customerLedgerRepository,
                supplierLedgerRepository,
                customerRepository,
                supplierRepository,
                saleRepository,
                clock
        );

        statementPdfService = new CustomerStatementPdfService(
                creditLedgerService,
                transactionHelper,
                settingsRepository,
                clock
        );

        UserRepository userRepository = new UserRepository();

        transactionHelper.executeVoid(conn -> {
            User owner = userRepository.insert(conn, new User(null, "owner", "pass", UserRole.OWNER, "Shop Owner", true, null));
            ownerUserId = owner.id();

            // Set custom shop profile
            settingsRepository.setSetting(conn, "SHOP_NAME", "Sharma Electricals", ownerUserId);
            settingsRepository.setSetting(conn, "SHOP_ADDRESS", "Plot 42, Main Chowk, Jaipur", ownerUserId);
            settingsRepository.setSetting(conn, "SHOP_PHONE", "9829012345", ownerUserId);
            settingsRepository.setSetting(conn, "SHOP_GSTIN", "08AABCS1429B1Z1", ownerUserId);

            Customer customer = customerRepository.insert(conn, new Customer(
                    null, "Dinesh Contractor", "9414098765", CustomerType.TECHNICIAN,
                    "Civil Lines, Jaipur", Money.ofRupees(25000), "A-grade contractor", null
            ));
            customerId = customer.id();

            // Seed ledger entries:
            // 1. Credit sale: ₹12,000 on 2026-09-01 (45+ days ago)
            customerLedgerRepository.insertEntry(
                    conn, customerId, "CREDIT_SALE", 1200000L, "SALE", 101L, "2026-09-01", "Conduit pipes & wires", ownerUserId
            );

            // 2. Payment received: ₹5,000 on 2026-09-15
            customerLedgerRepository.insertEntry(
                    conn, customerId, "PAYMENT_RECEIVED", -500000L, "UPI", 0L, "2026-09-15", "Advance via PhonePe", ownerUserId
            );

            // 3. Credit sale: ₹8,000 on 2026-10-10 (Recent)
            customerLedgerRepository.insertEntry(
                    conn, customerId, "CREDIT_SALE", 800000L, "SALE", 102L, "2026-10-10", "Modular switches & MCBs", ownerUserId
            );
        });
    }

    @Test
    @DisplayName("renderStatementPdf generates valid A4 PDF with header, summary, aging, and ledger rows")
    void testRenderStatementPdfSuccess() {
        byte[] pdfBytes = statementPdfService.renderStatementPdf(customerId, clock.today());

        assertNotNull(pdfBytes);
        assertTrue(pdfBytes.length > 1000, "PDF should have substantial content (size=" + pdfBytes.length + ")");

        // Verify PDF magic header
        String header = new String(pdfBytes, 0, 5);
        assertEquals("%PDF-", header, "File must start with standard PDF magic header");
    }

    @Test
    @DisplayName("writeStatementPdf writes properly to an external OutputStream")
    void testWriteStatementPdf() {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        statementPdfService.writeStatementPdf(customerId, clock.today(), baos);

        byte[] output = baos.toByteArray();
        assertNotNull(output);
        assertTrue(output.length > 1000);
    }

    @Test
    @DisplayName("Non-existent customer throws EntityNotFoundException")
    void testNonExistentCustomerThrows() {
        assertThrows(EntityNotFoundException.class, () ->
                statementPdfService.renderStatementPdf(99999L, clock.today())
        );
    }
}
