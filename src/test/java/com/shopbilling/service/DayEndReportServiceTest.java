package com.shopbilling.service;

import com.shopbilling.db.*;
import com.shopbilling.model.*;
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

@DisplayName("DayEndReportService Tests (Cash Reconciliation Audit Sheet & CSV Export)")
class DayEndReportServiceTest {

    @TempDir
    Path tempDir;

    private ConnectionFactory connectionFactory;
    private TransactionHelper transactionHelper;
    private DayEndClosingRepository dayEndClosingRepository;
    private ExpenseRepository expenseRepository;
    private SettingsRepository settingsRepository;
    private IstClock clock;

    private DayEndReportService dayEndReportService;

    private long ownerUserId;
    private LocalDate testDate;

    @BeforeEach
    void setUp() throws IOException {
        Path dbFile = tempDir.resolve("day_end_report_test.db");
        DatabaseConfig config = DatabaseConfig.forFile(dbFile);
        connectionFactory = new ConnectionFactory(config);
        new FlywayMigrator(connectionFactory).migrate();

        transactionHelper = new TransactionHelper(connectionFactory);
        dayEndClosingRepository = new DayEndClosingRepository();
        expenseRepository = new ExpenseRepository();
        settingsRepository = new SettingsRepository();
        clock = IstClock.system();

        dayEndReportService = new DayEndReportService(
                transactionHelper,
                dayEndClosingRepository,
                expenseRepository,
                settingsRepository,
                clock
        );

        UserRepository userRepository = new UserRepository();
        SaleRepository saleRepository = new SaleRepository();
        PaymentRepository paymentRepository = new PaymentRepository();
        CustomerRepository customerRepository = new CustomerRepository();
        CustomerLedgerRepository customerLedgerRepository = new CustomerLedgerRepository();

        testDate = LocalDate.of(2026, 10, 18);

        transactionHelper.executeVoid(conn -> {
            User owner = userRepository.insert(conn, new User(null, "owner", "pass", UserRole.OWNER, "Shop Owner", true, null));
            ownerUserId = owner.id();

            Customer customer = customerRepository.insert(conn, new Customer(null, "Pooja Stores", "9876500000", CustomerType.RETAIL, null, null, null, null));

            // 1. Cash Sale of ₹1500
            Sale sale = new Sale(
                    null, SaleMode.BILL, "2026-27", "FY26-27/0100", 100, customer.id(),
                    testDate, null, Money.ofRupees(1500), Money.ZERO, Money.ofRupees(1500),
                    ownerUserId, List.of(), List.of()
            );
            Sale savedSale = saleRepository.insertSale(conn, sale);
            paymentRepository.insertPayment(conn, savedSale.id(), new PaymentInput(PaymentMode.CASH, Money.ofRupees(1500), null));

            // 2. Cash Customer Payment Collection of ₹300
            customerLedgerRepository.insertEntry(
                    conn, customer.id(), "PAYMENT_RECEIVED", -30000L, "CASH", 0L, testDate.toString(), "Past dues clearance", ownerUserId
            );

            // 3. Cash Expense: ₹120 for Tea & Snacks
            expenseRepository.insertExpense(
                    conn, new ExpenseRecord(null, 1L, "MISC", testDate, Money.ofRupees(120), PaymentMode.CASH, "Evening tea and biscuits for shop", null, ownerUserId)
            );

            // 4. Record Day-End Closing: Expected 1500 + 300 - 120 = 1680. Counted = 1670 (diff = -10 shortage)
            dayEndClosingRepository.insertClosing(conn, new DayEndClosingRecord(
                    null, testDate, Money.ofRupees(1680), Money.ofRupees(1670), Money.ofRupees(-10), "Slight coin round-off", null, ownerUserId
            ));
        });
    }

    @Test
    @DisplayName("getDayEndReport correctly aggregates cash components, expenses, and closing records")
    void testGetDayEndReport() {
        DayEndReportModel report = dayEndReportService.getDayEndReport(testDate);

        assertNotNull(report);
        assertEquals(testDate, report.date());
        assertTrue(report.isClosed());
        assertTrue(report.closingRecord().isPresent());

        DayEndCashSummary cash = report.cashSummary();
        assertEquals(Money.ofRupees(1500), cash.cashSales());
        assertEquals(Money.ZERO, cash.cashRefunds());
        assertEquals(Money.ofRupees(300), cash.cashCustomerPayments());
        assertEquals(Money.ofRupees(120), cash.cashExpenses());
        assertEquals(Money.ofRupees(1680), cash.expectedCash());

        DayEndClosingRecord closing = report.closingRecord().get();
        assertEquals(Money.ofRupees(1670), closing.countedCash());
        assertEquals(Money.ofRupees(-10), closing.difference());

        assertEquals(1, report.expenses().size());
        assertEquals(Money.ofRupees(120), report.expenses().get(0).amount());
    }

    @Test
    @DisplayName("renderDayEndPdf generates valid PDF with cash breakdown and variance")
    void testRenderPdf() {
        byte[] pdf = dayEndReportService.renderDayEndPdf(testDate);

        assertNotNull(pdf);
        assertTrue(pdf.length > 1000);
        assertEquals("%PDF-", new String(pdf, 0, 5));
    }

    @Test
    @DisplayName("writeDayEndPdf writes to output stream")
    void testWriteStream() {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        dayEndReportService.writeDayEndPdf(testDate, baos);

        byte[] output = baos.toByteArray();
        assertNotNull(output);
        assertTrue(output.length > 1000);
    }

    @Test
    @DisplayName("exportClosingsCsv exports historical closing records formatted as CSV")
    void testExportCsv() {
        String csv = dayEndReportService.exportClosingsCsv(testDate.minusDays(1), testDate.plusDays(1));

        assertNotNull(csv);
        assertTrue(csv.contains("Date,Expected Cash (INR),Counted Cash (INR),Difference (INR)"));
        assertTrue(csv.contains("2026-10-18,1680.00,1670.00,-10.00"));
    }
}
