package com.shopbilling.service;

import com.shopbilling.db.DayEndClosingRepository;
import com.shopbilling.db.ExpenseRepository;
import com.shopbilling.db.TransactionHelper;
import com.shopbilling.model.DayEndCashSummary;
import com.shopbilling.model.DayEndClosingRecord;
import com.shopbilling.model.Money;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Service managing day-end register closing and cash reconciliation (PRD Section 7.9, FR-38 - FR-40).
 * Expected cash = cash sales - cash refunds + cash customer payments received - cash expenses.
 * Enforces owner-only closing and prevents duplicate closings for the same date.
 * Pure service: strictly contains NO JavaFX imports.
 */
public class DayEndClosingService {

    private static final Logger logger = LoggerFactory.getLogger(DayEndClosingService.class);

    private final TransactionHelper transactionHelper;
    private final DayEndClosingRepository dayEndClosingRepository;
    private final ExpenseRepository expenseRepository;
    private final IstClock clock;

    public DayEndClosingService(TransactionHelper transactionHelper,
                                DayEndClosingRepository dayEndClosingRepository,
                                ExpenseRepository expenseRepository,
                                IstClock clock) {
        this.transactionHelper = Objects.requireNonNull(transactionHelper, "TransactionHelper cannot be null");
        this.dayEndClosingRepository = Objects.requireNonNull(dayEndClosingRepository, "DayEndClosingRepository cannot be null");
        this.expenseRepository = Objects.requireNonNull(expenseRepository, "ExpenseRepository cannot be null");
        this.clock = Objects.requireNonNull(clock, "IstClock cannot be null");
    }

    /**
     * Calculates the daily cash summary for a given date according to the formula:
     * Expected cash = cash sales - cash refunds + cash customer payments received - cash expenses (FR-38).
     */
    public DayEndCashSummary calculateDailyCashSummary(LocalDate date) {
        final LocalDate targetDate = date != null ? date : clock.today();
        return transactionHelper.executeRead(conn -> calculateDailyCashSummaryInTransaction(conn, targetDate));
    }

    /**
     * Performs owner-only day-end register closing (FR-39, FR-40).
     *
     * @param date Date being closed (defaults to today)
     * @param countedCash Physical cash counted in register drawer by owner
     * @param notes Optional closing notes or variance explanation
     * @param userId User performing the close (must be OWNER)
     * @return Persisted DayEndClosingRecord with variance
     */
    public DayEndClosingRecord closeDay(LocalDate date,
                                        Money countedCash,
                                        String notes,
                                        long userId) {
        Objects.requireNonNull(countedCash, "Counted cash cannot be null");
        if (countedCash.isNegative()) {
            throw new ValidationException("Counted cash cannot be negative: " + countedCash);
        }

        final LocalDate effectiveDate = date != null ? date : clock.today();

        return transactionHelper.execute(conn -> {
            // 1. Verify user exists and check OWNER permission
            String role = getUserRole(conn, userId);
            if (!"OWNER".equalsIgnoreCase(role)) {
                throw new ValidationException("Only the shop owner is authorized to perform day-end closing");
            }

            // 2. Prevent duplicate close for the same calendar day
            Optional<DayEndClosingRecord> existing = dayEndClosingRepository.findByDate(conn, effectiveDate);
            if (existing.isPresent()) {
                throw new ValidationException(String.format(
                        "Day %s is already closed (closed on %s by user %d)",
                        effectiveDate, existing.get().closedAt(), existing.get().closedByUserId()
                ));
            }

            // 3. Compute expected cash and variance
            DayEndCashSummary cashSummary = calculateDailyCashSummaryInTransaction(conn, effectiveDate);
            Money expectedCash = cashSummary.expectedCash();
            Money difference = countedCash.minus(expectedCash);

            // 4. Insert closing record
            DayEndClosingRecord toInsert = new DayEndClosingRecord(
                    null,
                    effectiveDate,
                    expectedCash,
                    countedCash,
                    difference,
                    notes,
                    null,
                    userId
            );

            DayEndClosingRecord saved = dayEndClosingRepository.insertClosing(conn, toInsert);

            logger.info("Day-end closing completed: date={} expected={} counted={} diff={} user={}",
                    effectiveDate, expectedCash.toRupeesString(), countedCash.toRupeesString(),
                    difference.toRupeesString(), userId);

            return saved;
        });
    }

    public Optional<DayEndClosingRecord> getClosingForDate(LocalDate date) {
        Objects.requireNonNull(date, "Date cannot be null");
        return transactionHelper.executeRead(conn -> dayEndClosingRepository.findByDate(conn, date));
    }

    public List<DayEndClosingRecord> getAllClosings() {
        return transactionHelper.executeRead(dayEndClosingRepository::getAllClosings);
    }

    private DayEndCashSummary calculateDailyCashSummaryInTransaction(Connection conn, LocalDate date) throws SQLException {
        long cashSalesPaise = dayEndClosingRepository.getCashSalesForDate(conn, date);
        long cashRefundsPaise = dayEndClosingRepository.getCashRefundsForDate(conn, date);
        long cashCustPaymentsPaise = dayEndClosingRepository.getCashCustomerPaymentsForDate(conn, date);
        long cashExpensesPaise = expenseRepository.getTotalCashExpensesForDate(conn, date);

        return DayEndCashSummary.of(
                date,
                Money.ofPaise(cashSalesPaise),
                Money.ofPaise(cashRefundsPaise),
                Money.ofPaise(cashCustPaymentsPaise),
                Money.ofPaise(cashExpensesPaise)
        );
    }

    private String getUserRole(Connection conn, long userId) throws SQLException {
        String sql = "SELECT role FROM users WHERE id = ?;";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("role");
                }
            }
        }
        throw new EntityNotFoundException("User id=" + userId + " not found");
    }
}
