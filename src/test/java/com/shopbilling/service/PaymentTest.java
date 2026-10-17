package com.shopbilling.service;

import com.shopbilling.db.ConnectionFactory;
import com.shopbilling.db.CustomerRepository;
import com.shopbilling.db.DatabaseConfig;
import com.shopbilling.db.FlywayMigrator;
import com.shopbilling.db.PaymentRepository;
import com.shopbilling.db.TransactionHelper;
import com.shopbilling.model.Customer;
import com.shopbilling.model.CustomerType;
import com.shopbilling.model.Money;
import com.shopbilling.model.PaymentInput;
import com.shopbilling.model.PaymentMode;
import com.shopbilling.model.PaymentRecord;
import com.shopbilling.model.PaymentSplit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaymentTest {

    private ConnectionFactory connectionFactory;
    private TransactionHelper transactionHelper;
    private PaymentRepository paymentRepository;
    private CustomerRepository customerRepository;
    private PaymentValidationService validationService;

    private Customer technician;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws Exception {
        Path dbFile = tempDir.resolve("payment_test.db");
        DatabaseConfig config = DatabaseConfig.forFile(dbFile);
        connectionFactory = new ConnectionFactory(config);

        // Run migrations
        FlywayMigrator migrator = new FlywayMigrator(connectionFactory);
        migrator.migrate();

        // Seed user for foreign key references
        try (Connection conn = connectionFactory.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO users (id, username, password_hash, role, full_name) " +
                    "VALUES (1, 'staff', 'hash123', 'STAFF', 'Staff One');");
        }

        transactionHelper = new TransactionHelper(connectionFactory);
        paymentRepository = new PaymentRepository();
        customerRepository = new CustomerRepository();
        validationService = new PaymentValidationService(transactionHelper, customerRepository);

        // Seed a customer with Rs 10,000 credit limit
        transactionHelper.executeVoid(conn -> {
            technician = customerRepository.insert(conn, new Customer(
                    null,
                    "Sharma Electricals",
                    "9876500000",
                    CustomerType.TECHNICIAN,
                    "Bazar",
                    Money.ofRupees(10000), // Rs 10,000 credit limit
                    null,
                    null
            ));
        });
    }

    @Test
    @DisplayName("Should validate split payments matching total amount exactly")
    void shouldValidateMatchingSplitPayments() {
        Money total = Money.ofRupees(2500);

        // 2000 UPI + 500 Cash = 2500 Total
        PaymentSplit split = PaymentSplit.of(
                PaymentInput.of(PaymentMode.UPI, Money.ofRupees(2000), "UPI-TXN-123"),
                PaymentInput.of(PaymentMode.CASH, Money.ofRupees(500))
        );

        assertEquals(Money.ofRupees(2500), split.total());
        assertEquals(Money.ofRupees(2000), split.upiTotal());
        assertEquals(Money.ofRupees(500), split.cashTotal());
        assertDoesNotThrow(() -> split.validateAgainstTotal(total));
    }

    @Test
    @DisplayName("Should reject split payments if total is under or over allocated")
    void shouldRejectMismatchedPayments() {
        Money total = Money.ofRupees(1000);

        // Under-payment: 800 < 1000
        PaymentSplit under = PaymentSplit.single(PaymentMode.CASH, Money.ofRupees(800));
        ValidationException exUnder = assertThrows(ValidationException.class, () ->
                under.validateAgainstTotal(total)
        );
        assertTrue(exUnder.getMessage().contains("under-allocated"));

        // Over-payment: 1200 > 1000
        PaymentSplit over = PaymentSplit.single(PaymentMode.UPI, Money.ofRupees(1200));
        ValidationException exOver = assertThrows(ValidationException.class, () ->
                over.validateAgainstTotal(total)
        );
        assertTrue(exOver.getMessage().contains("over-allocated"));
    }

    @Test
    @DisplayName("Should reject negative or zero payment amounts")
    void shouldRejectInvalidPaymentAmounts() {
        assertThrows(IllegalArgumentException.class, () ->
                PaymentInput.of(PaymentMode.CASH, Money.ZERO)
        );
        assertThrows(IllegalArgumentException.class, () ->
                PaymentInput.of(PaymentMode.CASH, Money.ofPaise(-100))
        );
    }

    @Test
    @DisplayName("Credit sale must require an identified customer")
    void shouldRequireCustomerForCreditSale() {
        PaymentSplit creditSplit = PaymentSplit.single(PaymentMode.CREDIT, Money.ofRupees(500));

        try (Connection conn = connectionFactory.getConnection()) {
            // Null customer ID for credit sale must fail
            assertThrows(ValidationException.class, () ->
                    validationService.validatePayments(conn, creditSplit, Money.ofRupees(500), null)
            );
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("Credit sale within credit limit passes, exceeding credit limit fails")
    void shouldEnforceCustomerCreditLimit() throws Exception {
        try (Connection conn = connectionFactory.getConnection()) {
            // 1. Credit of Rs 8,000 (within Rs 10,000 limit) -> passes
            PaymentSplit validCredit = PaymentSplit.single(PaymentMode.CREDIT, Money.ofRupees(8000));
            assertDoesNotThrow(() ->
                    validationService.validatePayments(conn, validCredit, Money.ofRupees(8000), technician.id())
            );

            // 2. Simulate existing outstanding dues of Rs 8,000
            try (Statement stmt = conn.createStatement()) {
                stmt.execute(String.format(
                        "INSERT INTO customer_ledger_entries (customer_id, entry_type, amount_paise, reference_type, reference_id, entry_date, created_by_user_id) " +
                                "VALUES (%d, 'CREDIT_SALE', 800000, 'SALE', 1, '2026-10-17', 1);",
                        technician.id()
                ));
            }

            // 3. Attempting another credit of Rs 3,000 (8,000 + 3,000 = 11,000 > 10,000) -> fails!
            PaymentSplit excessCredit = PaymentSplit.single(PaymentMode.CREDIT, Money.ofRupees(3000));
            ValidationException ex = assertThrows(ValidationException.class, () ->
                    validationService.validatePayments(conn, excessCredit, Money.ofRupees(3000), technician.id())
            );
            assertTrue(ex.getMessage().contains("Credit limit exceeded"));
        }
    }

    @Test
    @DisplayName("Should persist and retrieve split payments in repository")
    void shouldPersistAndRetrievePayments() throws Exception {
        // Seed user and sale record for FK constraint
        try (Connection conn = connectionFactory.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO sales (id, sale_mode, financial_year, customer_id, sale_date, subtotal_paise, discount_paise, total_amount_paise, created_by_user_id) " +
                    "VALUES (101, 'REGISTERED', '2026-27', 1, '2026-10-17', 150000, 0, 150000, 1);");

            // Insert split: 1000 Card + 500 Cash
            paymentRepository.insertPayment(conn, 101, PaymentInput.of(PaymentMode.CARD, Money.ofRupees(1000), "CARD-4422"));
            paymentRepository.insertPayment(conn, 101, PaymentInput.of(PaymentMode.CASH, Money.ofRupees(500)));

            List<PaymentRecord> records = paymentRepository.findBySaleId(conn, 101);
            assertEquals(2, records.size());
            assertEquals(PaymentMode.CARD, records.get(0).mode());
            assertEquals(Money.ofRupees(1000), records.get(0).amount());
            assertEquals("CARD-4422", records.get(0).referenceNumber());

            assertEquals(PaymentMode.CASH, records.get(1).mode());
            assertEquals(Money.ofRupees(500), records.get(1).amount());
        }
    }
}
