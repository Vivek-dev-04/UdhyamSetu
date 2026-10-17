package com.shopbilling.service;

import com.shopbilling.db.BillCounterRepository;
import com.shopbilling.db.TransactionHelper;
import com.shopbilling.model.BillNumber;
import com.shopbilling.model.FinancialYear;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;

/**
 * Service managing atomic per-financial-year bill counters (PRD Section 6 & Hard Rule 6).
 * Ensures gap-free numbering and strict rollback on failed transactions.
 * Strictly contains NO JavaFX imports.
 */
public class BillCounterService {

    private static final Logger logger = LoggerFactory.getLogger(BillCounterService.class);

    private final TransactionHelper transactionHelper;
    private final BillCounterRepository billCounterRepository;

    public BillCounterService(TransactionHelper transactionHelper, BillCounterRepository billCounterRepository) {
        this.transactionHelper = Objects.requireNonNull(transactionHelper, "TransactionHelper cannot be null");
        this.billCounterRepository = Objects.requireNonNull(billCounterRepository, "BillCounterRepository cannot be null");
    }

    /**
     * Atomically increments and returns the next bill number inside an ongoing transaction.
     * Used by Generate Bill (M13).
     */
    public BillNumber nextBillNumberInTransaction(Connection conn, FinancialYear financialYear) throws SQLException {
        BillNumber billNumber = billCounterRepository.nextBillNumber(conn, financialYear);
        logger.info("Assigned bill number: {} for FY: {}", billNumber.formattedNumber(), financialYear.getLabel());
        return billNumber;
    }

    /**
     * Atomically increments and returns the next bill number in its own dedicated transaction.
     */
    public BillNumber nextBillNumber(FinancialYear financialYear) {
        return transactionHelper.execute(conn -> nextBillNumberInTransaction(conn, financialYear));
    }

    public Optional<BillNumber> getCurrentBillNumber(FinancialYear financialYear) {
        return transactionHelper.executeRead(conn -> billCounterRepository.getCurrentBillNumber(conn, financialYear));
    }
}
