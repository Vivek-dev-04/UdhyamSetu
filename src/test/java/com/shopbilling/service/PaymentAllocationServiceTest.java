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

@DisplayName("PaymentAllocationService Tests (Outstanding Credit Invoices & FIFO Payment Allocations)")
class PaymentAllocationServiceTest {

    @TempDir
    Path tempDir;

    private ConnectionFactory connectionFactory;
    private TransactionHelper transactionHelper;
    private CustomerRepository customerRepository;
    private CustomerLedgerRepository customerLedgerRepository;
    private SaleRepository saleRepository;
    private PaymentRepository paymentRepository;

    private PaymentAllocationService allocationService;

    private long customerId;
    private long ownerUserId;
    private long sale1Id;
    private long sale2Id;

    @BeforeEach
    void setUp() throws IOException {
        Path dbFile = tempDir.resolve("payment_allocation_test.db");
        DatabaseConfig config = DatabaseConfig.forFile(dbFile);
        connectionFactory = new ConnectionFactory(config);
        new FlywayMigrator(connectionFactory).migrate();

        transactionHelper = new TransactionHelper(connectionFactory);
        customerRepository = new CustomerRepository();
        customerLedgerRepository = new CustomerLedgerRepository();
        saleRepository = new SaleRepository();
        paymentRepository = new PaymentRepository();

        allocationService = new PaymentAllocationService(
                transactionHelper,
                customerRepository,
                customerLedgerRepository,
                saleRepository
        );

        UserRepository userRepository = new UserRepository();

        transactionHelper.executeVoid(conn -> {
            User owner = userRepository.insert(conn, new User(null, "owner", "pass", UserRole.OWNER, "Shop Owner", true, null));
            ownerUserId = owner.id();

            Customer customer = customerRepository.insert(conn, new Customer(
                    null, "Ramesh Electric Works", "9876543210", CustomerType.TECHNICIAN, "Market", Money.ofRupees(50000), "Good client", null
            ));
            customerId = customer.id();

            // Create Sale 1: Total ₹500 on Credit, Date: 2026-10-01
            Sale s1 = new Sale(
                    null, SaleMode.BILL, "2026-27", "FY26-27/0001", 1, customerId,
                    LocalDate.of(2026, 10, 1), null, Money.ofRupees(500), Money.ZERO, Money.ofRupees(500),
                    ownerUserId, List.of(), List.of()
            );
            Sale saved1 = saleRepository.insertSale(conn, s1);
            sale1Id = saved1.id();
            paymentRepository.insertPayment(conn, sale1Id, new PaymentInput(PaymentMode.CREDIT, Money.ofRupees(500), null));

            // Create Sale 2: Total ₹800 on Credit, Date: 2026-10-05
            Sale s2 = new Sale(
                    null, SaleMode.BILL, "2026-27", "FY26-27/0002", 2, customerId,
                    LocalDate.of(2026, 10, 5), null, Money.ofRupees(800), Money.ZERO, Money.ofRupees(800),
                    ownerUserId, List.of(), List.of()
            );
            Sale saved2 = saleRepository.insertSale(conn, s2);
            sale2Id = saved2.id();
            paymentRepository.insertPayment(conn, sale2Id, new PaymentInput(PaymentMode.CREDIT, Money.ofRupees(800), null));
        });
    }

    @Test
    @DisplayName("getCreditSalesStatus correctly tracks credit amounts, past allocations, and remaining balances")
    void testGetCreditSalesStatus() {
        // Initially no allocations: Sale 1 balance 500, Sale 2 balance 800
        List<OutstandingSaleItem> items = allocationService.getCreditSalesStatus(customerId, true);
        assertEquals(2, items.size());

        OutstandingSaleItem item1 = items.get(0);
        assertEquals(sale1Id, item1.saleId());
        assertEquals("FY26-27/0001", item1.billNumber());
        assertEquals(Money.ofRupees(500), item1.totalAmount());
        assertEquals(Money.ofRupees(500), item1.creditAmount());
        assertEquals(Money.ZERO, item1.allocatedAmount());
        assertEquals(Money.ofRupees(500), item1.outstandingBalance());
        assertFalse(item1.isFullyPaid());

        OutstandingSaleItem item2 = items.get(1);
        assertEquals(sale2Id, item2.saleId());
        assertEquals(Money.ofRupees(800), item2.outstandingBalance());
    }

    @Test
    @DisplayName("computeFifoAllocations allocates payment to oldest sales first in strict FIFO order")
    void testComputeFifoAllocations() {
        // Customer pays ₹700: should fully cover Sale 1 (₹500) and partially cover Sale 2 (₹200)
        List<SaleAllocationInput> allocations = allocationService.computeFifoAllocations(customerId, Money.ofRupees(700));

        assertNotNull(allocations);
        assertEquals(2, allocations.size());

        assertEquals(sale1Id, allocations.get(0).saleId());
        assertEquals(Money.ofRupees(500), allocations.get(0).amount(), "First allocation should cover Sale 1 in full");

        assertEquals(sale2Id, allocations.get(1).saleId());
        assertEquals(Money.ofRupees(200), allocations.get(1).amount(), "Second allocation covers remaining ₹200 for Sale 2");
    }

    @Test
    @DisplayName("When payment exceeds all outstanding dues, FIFO allocates exact amounts up to balances")
    void testComputeFifoExcessPayment() {
        // Total dues are 500 + 800 = 1300. Payment is 2000.
        List<SaleAllocationInput> allocations = allocationService.computeFifoAllocations(customerId, Money.ofRupees(2000));

        assertEquals(2, allocations.size());
        assertEquals(Money.ofRupees(500), allocations.get(0).amount());
        assertEquals(Money.ofRupees(800), allocations.get(1).amount());
    }

    @Test
    @DisplayName("After recording allocations in DB, unpaidOnly filter hides fully paid sales")
    void testAllocationsRecordedAndFilter() {
        transactionHelper.executeVoid(conn -> {
            long entryId = customerLedgerRepository.insertEntry(
                    conn, customerId, "PAYMENT_RECEIVED", -50000L, "CASH", 0L, "2026-10-10", "Payment", ownerUserId
            );
            customerLedgerRepository.insertAllocation(conn, entryId, sale1Id, 50000L);
        });

        // Now Sale 1 is fully paid, only Sale 2 should appear in unpaidOnly list
        List<OutstandingSaleItem> unpaid = allocationService.getCreditSalesStatus(customerId, true);
        assertEquals(1, unpaid.size());
        assertEquals(sale2Id, unpaid.get(0).saleId());
        assertEquals(Money.ofRupees(800), unpaid.get(0).outstandingBalance());

        // But when unpaidOnly is false, both appear
        List<OutstandingSaleItem> all = allocationService.getCreditSalesStatus(customerId, false);
        assertEquals(2, all.size());
        assertTrue(all.get(0).isFullyPaid());
        assertFalse(all.get(1).isFullyPaid());

        // Verify allocations for sale 1
        List<PaymentAllocation> allocs = allocationService.getAllocationsForSale(sale1Id);
        assertEquals(1, allocs.size());
        assertEquals(Money.ofRupees(500), allocs.get(0).allocatedAmount());
    }
}
