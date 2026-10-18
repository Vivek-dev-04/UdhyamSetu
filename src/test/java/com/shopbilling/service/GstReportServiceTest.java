package com.shopbilling.service;

import com.shopbilling.db.ConnectionFactory;
import com.shopbilling.db.CustomerRepository;
import com.shopbilling.db.DatabaseConfig;
import com.shopbilling.db.FlywayMigrator;
import com.shopbilling.db.ProductRepository;
import com.shopbilling.db.ProductVariantRepository;
import com.shopbilling.db.ReturnRepository;
import com.shopbilling.db.SaleRepository;
import com.shopbilling.db.SettingsRepository;
import com.shopbilling.db.TransactionHelper;
import com.shopbilling.db.UserRepository;
import com.shopbilling.model.Customer;
import com.shopbilling.model.CustomerType;
import com.shopbilling.model.FinancialYear;
import com.shopbilling.model.GstInvoiceRow;
import com.shopbilling.model.GstReportResult;
import com.shopbilling.model.Money;
import com.shopbilling.model.Quantity;
import com.shopbilling.model.Quarter;
import com.shopbilling.model.ReturnCondition;
import com.shopbilling.model.ReturnItemRecord;
import com.shopbilling.model.ReturnRecord;
import com.shopbilling.model.Sale;
import com.shopbilling.model.SaleItem;
import com.shopbilling.model.SaleMode;
import com.shopbilling.model.User;
import com.shopbilling.model.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("GstReportService Tests (GST Compliance, B2B/B2C Invoices & CA Export)")
class GstReportServiceTest {

    @TempDir
    Path tempDir;

    private ConnectionFactory connectionFactory;
    private TransactionHelper transactionHelper;
    private SettingsRepository settingsRepository;
    private IstClock clock;

    private GstReportService gstReportService;

    private final LocalDate testDateQ3 = LocalDate.of(2026, 10, 15);
    private final LocalDate testDateQ1 = LocalDate.of(2026, 5, 20);

    @BeforeEach
    void setUp() throws IOException {
        Path dbFile = tempDir.resolve("gst_report_test.db");
        DatabaseConfig config = DatabaseConfig.forFile(dbFile);
        connectionFactory = new ConnectionFactory(config);
        new FlywayMigrator(connectionFactory).migrate();

        transactionHelper = new TransactionHelper(connectionFactory);
        settingsRepository = new SettingsRepository();
        clock = IstClock.fixedAtStartOfDay(testDateQ3);

        gstReportService = new GstReportService(transactionHelper, settingsRepository, clock);

        UserRepository userRepository = new UserRepository();
        CustomerRepository customerRepository = new CustomerRepository();
        SaleRepository saleRepository = new SaleRepository();
        ReturnRepository returnRepository = new ReturnRepository();

        transactionHelper.executeVoid(conn -> {
            User owner = userRepository.insert(conn, new User(null, "owner", "pass", UserRole.OWNER, "Shop Owner", true, null));
            settingsRepository.setSetting(conn, "GST_RATE_PERCENT", "18.0", owner.id());

            // Customer 1: B2B Technician with valid GSTIN in notes
            Customer b2bCustomer = customerRepository.insert(conn, new Customer(
                    null, "Apex Power Tech", "9876543210", CustomerType.TECHNICIAN, "Industrial Area", null, "GSTIN: 08ABCDE1234F1Z5", null
            ));

            // Customer 2: B2C Retail customer without GSTIN
            Customer b2cCustomer = customerRepository.insert(conn, new Customer(
                    null, "Ramesh Kumar", "9123456789", CustomerType.RETAIL, "Civil Lines", null, null, null
            ));

            // 1. Billed Sale 1 (B2B): ₹11,800 total (Taxable = ₹10,000, CGST = ₹900, SGST = ₹900)
            Sale s1 = new Sale(
                    null, SaleMode.BILL, "2026-27", "FY26-27/0001", 1, b2bCustomer.id(),
                    testDateQ3, null, Money.ofRupees(11800), Money.ZERO, Money.ofRupees(11800),
                    owner.id(), List.of(), List.of()
            );
            Sale savedS1 = saleRepository.insertSale(conn, s1);

            // 2. Billed Sale 2 (B2C): ₹5,900 total (Taxable = ₹5,000, CGST = ₹450, SGST = ₹450)
            Sale s2 = new Sale(
                    null, SaleMode.BILL, "2026-27", "FY26-27/0002", 2, b2cCustomer.id(),
                    testDateQ3, null, Money.ofRupees(5900), Money.ZERO, Money.ofRupees(5900),
                    owner.id(), List.of(), List.of()
            );
            Sale savedS2 = saleRepository.insertSale(conn, s2);

            // 3. Registered (Unbilled) Sale: ₹2,500 total
            Sale s3 = new Sale(
                    null, SaleMode.REGISTERED, "2026-27", null, null, b2cCustomer.id(),
                    testDateQ3, null, Money.ofRupees(2500), Money.ZERO, Money.ofRupees(2500),
                    owner.id(), List.of(), List.of()
            );
            saleRepository.insertSale(conn, s3);

            // 4. Billed Sale in Q1: ₹1,180
            Sale s4 = new Sale(
                    null, SaleMode.BILL, "2026-27", "FY26-27/0003", 3, b2cCustomer.id(),
                    testDateQ1, null, Money.ofRupees(1180), Money.ZERO, Money.ofRupees(1180),
                    owner.id(), List.of(), List.of()
            );
            saleRepository.insertSale(conn, s4);

            // 5. Credit Note Return on Sale 1: ₹1,180 refund (CN26-27/0001)
            ReturnRecord ret = new ReturnRecord(
                    null, savedS1.id(), "CN26-27/0001", "2026-27", testDateQ3, com.shopbilling.model.PaymentMode.CASH,
                    Money.ofRupees(1180), "Wrong voltage spec", null, owner.id(), List.of()
            );
            returnRepository.insertReturn(conn, ret);
        });
    }

    @Test
    @DisplayName("generateGstReport calculates full financial year GST turnover, B2B/B2C, and credit note deductions")
    void testGenerateGstReportYearly() {
        FinancialYear fy = FinancialYear.of(2026);
        GstReportResult report = gstReportService.generateGstReport(fy, null, null);

        assertNotNull(report);
        assertEquals(3, report.invoices().size()); // 3 billed invoices across the FY

        // Verify B2B classification
        GstInvoiceRow b2bInvoice = report.invoices().stream()
                .filter(inv -> "FY26-27/0001".equals(inv.billNumber()))
                .findFirst().orElseThrow();
        assertTrue(b2bInvoice.isB2B());
        assertEquals("08ABCDE1234F1Z5", b2bInvoice.customerGstin());
        assertEquals(Money.ofRupees(11800), b2bInvoice.invoiceTotal());
        assertEquals(Money.ofRupees(10000), b2bInvoice.taxableValue());
        assertEquals(Money.ofRupees(900), b2bInvoice.cgst());
        assertEquals(Money.ofRupees(900), b2bInvoice.sgst());
        assertEquals(Money.ofRupees(1800), b2bInvoice.totalTax());

        // Verify B2C classification
        GstInvoiceRow b2cInvoice = report.invoices().stream()
                .filter(inv -> "FY26-27/0002".equals(inv.billNumber()))
                .findFirst().orElseThrow();
        assertFalse(b2cInvoice.isB2B());
        assertEquals(Money.ofRupees(5900), b2cInvoice.invoiceTotal());
        assertEquals(Money.ofRupees(5000), b2cInvoice.taxableValue());

        // Verify Grand Total Summary
        // Gross Billed = 11800 + 5900 + 1180 = 18880
        // Credit Notes = 1180
        // Net Billed = 18880 - 1180 = 17700
        // Registered Turnover = 2500
        // Total Turnover = 17700 + 2500 = 20200
        assertEquals(Money.ofRupees(18880), report.totalSummary().grossBilledTurnover());
        assertEquals(Money.ofRupees(1180), report.totalSummary().creditNoteDeductions());
        assertEquals(Money.ofRupees(17700), report.totalSummary().netBilledTurnover());
        assertEquals(Money.ofRupees(2500), report.totalSummary().registeredTurnover());
        assertEquals(Money.ofRupees(20200), report.totalSummary().totalTurnover());
    }

    @Test
    @DisplayName("generateGstReport filters correctly by Quarter (Q3: Oct - Dec)")
    void testQuarterlyReport() {
        FinancialYear fy = FinancialYear.of(2026);
        GstReportResult report = gstReportService.generateGstReport(fy, Quarter.Q3, null);

        assertEquals(2, report.invoices().size()); // Only S1 and S2 in October
        assertEquals(Money.ofRupees(17700), report.totalSummary().grossBilledTurnover()); // 11800 + 5900
        assertEquals(Money.ofRupees(1180), report.totalSummary().creditNoteDeductions());
        assertEquals(Money.ofRupees(16520), report.totalSummary().netBilledTurnover()); // 17700 - 1180
        assertEquals(Money.ofRupees(2500), report.totalSummary().registeredTurnover());
    }

    @Test
    @DisplayName("exportGstReportCsv exports valid CSV with CA summary and invoice details")
    void testExportCsv() {
        FinancialYear fy = FinancialYear.of(2026);
        GstReportResult report = gstReportService.generateGstReport(fy, Quarter.Q3, null);

        String csv = gstReportService.exportGstReportCsvString(report);
        assertNotNull(csv);
        assertTrue(csv.contains("GST & CA COMPLIANCE TURNOVER SUMMARY"));
        assertTrue(csv.contains("Apex Power Tech"));
        assertTrue(csv.contains("08ABCDE1234F1Z5"));
        assertTrue(csv.contains("FY26-27/0001"));
        assertTrue(csv.contains("B2B"));
        assertTrue(csv.contains("B2C"));
    }

    @Test
    @DisplayName("generateGstReportForDateRange validates date constraints")
    void testDateRangeValidation() {
        assertThrows(ValidationException.class, () ->
                gstReportService.generateGstReportForDateRange(LocalDate.of(2026, 12, 1), LocalDate.of(2026, 11, 1), null)
        );
    }
}
