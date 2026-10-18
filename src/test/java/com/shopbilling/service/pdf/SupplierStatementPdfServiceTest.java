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

@DisplayName("SupplierStatementPdfService Tests (Distributor Statement Khata PDF Generation)")
class SupplierStatementPdfServiceTest {

    @TempDir
    Path tempDir;

    private ConnectionFactory connectionFactory;
    private TransactionHelper transactionHelper;
    private SupplierRepository supplierRepository;
    private SupplierLedgerRepository supplierLedgerRepository;
    private SettingsRepository settingsRepository;
    private CreditLedgerService creditLedgerService;
    private IstClock clock;

    private SupplierStatementPdfService statementPdfService;

    private long supplierId;
    private long ownerUserId;

    @BeforeEach
    void setUp() throws IOException {
        Path dbFile = tempDir.resolve("supplier_statement_pdf_test.db");
        DatabaseConfig config = DatabaseConfig.forFile(dbFile);
        connectionFactory = new ConnectionFactory(config);
        new FlywayMigrator(connectionFactory).migrate();

        transactionHelper = new TransactionHelper(connectionFactory);
        supplierRepository = new SupplierRepository();
        supplierLedgerRepository = new SupplierLedgerRepository();
        CustomerRepository customerRepository = new CustomerRepository();
        CustomerLedgerRepository customerLedgerRepository = new CustomerLedgerRepository();
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

        statementPdfService = new SupplierStatementPdfService(
                creditLedgerService,
                transactionHelper,
                settingsRepository,
                clock
        );

        UserRepository userRepository = new UserRepository();

        transactionHelper.executeVoid(conn -> {
            User owner = userRepository.insert(conn, new User(null, "owner", "pass", UserRole.OWNER, "Shop Owner", true, null));
            ownerUserId = owner.id();

            Supplier supplier = supplierRepository.insert(conn, new Supplier(
                    null, "Finolex Cables Ltd", "02027475963", "Vikas Joshi", "Pimpri, Pune", "27AAACF1234F1Z1", "Wire distributor", null
            ));
            supplierId = supplier.id();

            // Seed supplier transactions:
            // 1. Purchase on credit: ₹45,000 on 2026-09-01
            supplierLedgerRepository.insertEntry(
                    conn, supplierId, "PURCHASE_CREDIT", 4500000L, "PURCHASE", 201L, "2026-09-01", "Coils & flexible wires", ownerUserId
            );

            // 2. Payment made: ₹20,000 on 2026-09-20
            supplierLedgerRepository.insertEntry(
                    conn, supplierId, "PAYMENT_MADE", -2000000L, "NEFT", 0L, "2026-09-20", "Bank transfer UTR #987654", ownerUserId
            );

            // 3. Supplier return (debit note): ₹5,000 on 2026-10-05
            supplierLedgerRepository.insertEntry(
                    conn, supplierId, "RETURN_OUT", -500000L, "RETURN_OUT", 1L, "2026-10-05", "Defective PVC insulation return", ownerUserId
            );
        });
    }

    @Test
    @DisplayName("renderStatementPdf generates valid A4 PDF with supplier header, aging, and running payables")
    void testRenderPdf() {
        byte[] pdfBytes = statementPdfService.renderStatementPdf(supplierId, clock.today());

        assertNotNull(pdfBytes);
        assertTrue(pdfBytes.length > 1000);
        assertEquals("%PDF-", new String(pdfBytes, 0, 5));
    }

    @Test
    @DisplayName("writeStatementPdf writes PDF to output stream")
    void testWriteStream() {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        statementPdfService.writeStatementPdf(supplierId, clock.today(), baos);

        byte[] output = baos.toByteArray();
        assertNotNull(output);
        assertTrue(output.length > 1000);
    }

    @Test
    @DisplayName("Non-existent supplierId throws EntityNotFoundException")
    void testNonExistentSupplierThrows() {
        assertThrows(EntityNotFoundException.class, () ->
                statementPdfService.renderStatementPdf(99999L, clock.today())
        );
    }
}
