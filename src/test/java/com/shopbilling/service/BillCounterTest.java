package com.shopbilling.service;

import com.shopbilling.db.BillCounterRepository;
import com.shopbilling.db.ConnectionFactory;
import com.shopbilling.db.DatabaseConfig;
import com.shopbilling.db.FlywayMigrator;
import com.shopbilling.db.TransactionException;
import com.shopbilling.db.TransactionHelper;
import com.shopbilling.model.BillNumber;
import com.shopbilling.model.FinancialYear;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BillCounterTest {

    private TransactionHelper transactionHelper;
    private BillCounterService billCounterService;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        Path dbFile = tempDir.resolve("bill_counter_test.db");
        DatabaseConfig config = DatabaseConfig.forFile(dbFile);
        ConnectionFactory connectionFactory = new ConnectionFactory(config);

        // Run migrations
        FlywayMigrator migrator = new FlywayMigrator(connectionFactory);
        migrator.migrate();

        transactionHelper = new TransactionHelper(connectionFactory);
        BillCounterRepository repository = new BillCounterRepository();
        billCounterService = new BillCounterService(transactionHelper, repository);
    }

    @Test
    @DisplayName("Should increment gap-free numbers with correct formatting for a financial year")
    void shouldIncrementGapFreeNumbers() {
        FinancialYear fy = FinancialYear.of(2024); // FY 2024-25

        BillNumber b1 = billCounterService.nextBillNumber(fy);
        assertEquals(1, b1.sequenceNumber());
        assertEquals("FY24-25/0001", b1.formattedNumber());

        BillNumber b2 = billCounterService.nextBillNumber(fy);
        assertEquals(2, b2.sequenceNumber());
        assertEquals("FY24-25/0002", b2.formattedNumber());

        BillNumber b3 = billCounterService.nextBillNumber(fy);
        assertEquals(3, b3.sequenceNumber());
        assertEquals("FY24-25/0003", b3.formattedNumber());

        Optional<BillNumber> current = billCounterService.getCurrentBillNumber(fy);
        assertTrue(current.isPresent());
        assertEquals(3, current.get().sequenceNumber());
    }

    @Test
    @DisplayName("Financial year boundary: counters maintain independent sequence per financial year")
    void shouldMaintainIndependentCountersPerFinancialYear() {
        FinancialYear fy24 = FinancialYear.of(2024); // FY 2024-25
        FinancialYear fy25 = FinancialYear.of(2025); // FY 2025-26

        BillNumber b1_24 = billCounterService.nextBillNumber(fy24);
        BillNumber b2_24 = billCounterService.nextBillNumber(fy24);

        assertEquals("FY24-25/0001", b1_24.formattedNumber());
        assertEquals("FY24-25/0002", b2_24.formattedNumber());

        // New financial year begins: counter restarts at 1!
        BillNumber b1_25 = billCounterService.nextBillNumber(fy25);
        assertEquals(1, b1_25.sequenceNumber());
        assertEquals("FY25-26/0001", b1_25.formattedNumber());

        // Subsequent call in previous FY continues from 3 without collision
        BillNumber b3_24 = billCounterService.nextBillNumber(fy24);
        assertEquals(3, b3_24.sequenceNumber());
        assertEquals("FY24-25/0003", b3_24.formattedNumber());
    }

    @Test
    @DisplayName("Rollback test: failed transaction rolls back bill number counter preserving gap-free sequence")
    void shouldRollbackCounterOnFailure() {
        FinancialYear fy = FinancialYear.of(2024);

        // First successful bill
        BillNumber b1 = billCounterService.nextBillNumber(fy);
        assertEquals(1, b1.sequenceNumber());

        // Second bill starts in transaction but fails and rolls back
        assertThrows(IllegalStateException.class, () ->
                transactionHelper.executeVoid(conn -> {
                    BillNumber inTx = billCounterService.nextBillNumberInTransaction(conn, fy);
                    assertEquals(2, inTx.sequenceNumber());
                    throw new IllegalStateException("Forced crash during sale persistence!");
                })
        );

        // Next bill must take sequence 2 (no gap left!)
        BillNumber b2 = billCounterService.nextBillNumber(fy);
        assertEquals(2, b2.sequenceNumber());
        assertEquals("FY24-25/0002", b2.formattedNumber());
    }

    @Test
    @DisplayName("Concurrent calls generate unique, distinct numbers without duplicates")
    void shouldHandleConcurrentIncrementsSafely() throws InterruptedException {
        FinancialYear fy = FinancialYear.of(2024);
        int threadCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(1);
        List<BillNumber> generated = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    latch.await();
                    BillNumber bn = billCounterService.nextBillNumber(fy);
                    generated.add(bn);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        }

        latch.countDown();
        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

        assertEquals(threadCount, generated.size());

        // Verify all sequence numbers from 1 to threadCount exist uniquely
        Set<Long> sequences = new HashSet<>();
        for (BillNumber bn : generated) {
            sequences.add(bn.sequenceNumber());
        }

        assertEquals(threadCount, sequences.size(), "All generated sequence numbers must be distinct");
        for (long seq = 1; seq <= threadCount; seq++) {
            assertTrue(sequences.contains(seq), "Sequence number missing: " + seq);
        }
    }
}
