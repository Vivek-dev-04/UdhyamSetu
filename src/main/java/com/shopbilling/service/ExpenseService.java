package com.shopbilling.service;

import com.shopbilling.db.ExpenseRepository;
import com.shopbilling.db.TransactionHelper;
import com.shopbilling.model.ExpenseCategory;
import com.shopbilling.model.ExpenseRecord;
import com.shopbilling.model.Money;
import com.shopbilling.model.PaymentMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Service managing shop expense recording and categories (PRD Section 7.7, FR-31, FR-32).
 * Expenses never affect stock or sales; they feed the profit view and day-end cash.
 * Pure service: strictly contains NO JavaFX imports.
 */
public class ExpenseService {

    private static final Logger logger = LoggerFactory.getLogger(ExpenseService.class);

    private final TransactionHelper transactionHelper;
    private final ExpenseRepository expenseRepository;
    private final IstClock clock;

    public ExpenseService(TransactionHelper transactionHelper,
                          ExpenseRepository expenseRepository,
                          IstClock clock) {
        this.transactionHelper = Objects.requireNonNull(transactionHelper, "TransactionHelper cannot be null");
        this.expenseRepository = Objects.requireNonNull(expenseRepository, "ExpenseRepository cannot be null");
        this.clock = Objects.requireNonNull(clock, "IstClock cannot be null");
    }

    public ExpenseCategory createCategory(String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new ValidationException("Category name cannot be blank");
        }
        return transactionHelper.execute(conn -> {
            Optional<ExpenseCategory> existing = expenseRepository.findCategoryByName(conn, name);
            if (existing.isPresent()) {
                throw new DuplicateEntityException("Expense category '" + name.trim().toUpperCase() + "' already exists");
            }
            ExpenseCategory cat = expenseRepository.createCategory(conn, name);
            logger.info("Created expense category id={} name='{}'", cat.id(), cat.name());
            return cat;
        });
    }

    public List<ExpenseCategory> getAllCategories() {
        return transactionHelper.executeRead(expenseRepository::getAllCategories);
    }

    public ExpenseRecord recordExpense(long categoryId,
                                       Money amount,
                                       PaymentMode paymentMode,
                                       LocalDate expenseDate,
                                       String note,
                                       long userId) {
        Objects.requireNonNull(amount, "Expense amount cannot be null");
        Objects.requireNonNull(paymentMode, "Payment mode cannot be null");
        if (!amount.isPositive()) {
            throw new ValidationException("Expense amount must be strictly positive: " + amount);
        }

        final LocalDate effectiveDate = expenseDate != null ? expenseDate : clock.today();

        return transactionHelper.execute(conn -> {
            ExpenseCategory category = expenseRepository.findCategoryById(conn, categoryId)
                    .orElseThrow(() -> new EntityNotFoundException("Expense category id=" + categoryId + " not found"));

            ExpenseRecord toInsert = new ExpenseRecord(
                    null,
                    categoryId,
                    category.name(),
                    effectiveDate,
                    amount,
                    paymentMode,
                    note,
                    null,
                    userId
            );

            ExpenseRecord saved = expenseRepository.insertExpense(conn, toInsert);
            logger.info("Recorded expense id={} category='{}' amount={} mode={}",
                    saved.id(), category.name(), amount.toRupeesString(), paymentMode);
            return saved;
        });
    }

    public List<ExpenseRecord> getExpensesForDateRange(LocalDate from, LocalDate to) {
        Objects.requireNonNull(from, "From date cannot be null");
        Objects.requireNonNull(to, "To date cannot be null");
        if (from.isAfter(to)) {
            throw new ValidationException("From date (" + from + ") cannot be after to date (" + to + ")");
        }
        return transactionHelper.executeRead(conn -> expenseRepository.getExpensesByDateRange(conn, from, to));
    }

    public List<ExpenseRecord> getExpensesForDate(LocalDate date) {
        Objects.requireNonNull(date, "Date cannot be null");
        return transactionHelper.executeRead(conn -> expenseRepository.getExpensesByDate(conn, date));
    }
}
