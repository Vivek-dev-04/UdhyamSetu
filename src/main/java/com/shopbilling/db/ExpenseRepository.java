package com.shopbilling.db;

import com.shopbilling.model.ExpenseCategory;
import com.shopbilling.model.ExpenseRecord;
import com.shopbilling.model.Money;
import com.shopbilling.model.PaymentMode;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Parameterized SQL repository for expense categories and expenses (PRD Section 7.7, 11).
 * Append-only: expense records are never modified or deleted.
 */
public class ExpenseRepository {

    public ExpenseCategory createCategory(Connection conn, String name) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        Objects.requireNonNull(name, "Category name cannot be null");

        String sql = "INSERT INTO expense_categories (name) VALUES (?);";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name.trim().toUpperCase());
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    return new ExpenseCategory(rs.getLong(1), name.trim().toUpperCase());
                }
            }
        }
        throw new DatabaseException("Failed to insert expense category");
    }

    public List<ExpenseCategory> getAllCategories(Connection conn) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        String sql = "SELECT id, name FROM expense_categories ORDER BY name ASC;";
        List<ExpenseCategory> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(new ExpenseCategory(rs.getLong("id"), rs.getString("name")));
            }
        }
        return list;
    }

    public Optional<ExpenseCategory> findCategoryById(Connection conn, long id) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        String sql = "SELECT id, name FROM expense_categories WHERE id = ?;";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new ExpenseCategory(rs.getLong("id"), rs.getString("name")));
                }
            }
        }
        return Optional.empty();
    }

    public Optional<ExpenseCategory> findCategoryByName(Connection conn, String name) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        String sql = "SELECT id, name FROM expense_categories WHERE UPPER(name) = ?;";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name.trim().toUpperCase());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new ExpenseCategory(rs.getLong("id"), rs.getString("name")));
                }
            }
        }
        return Optional.empty();
    }

    public ExpenseRecord insertExpense(Connection conn, ExpenseRecord expense) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        Objects.requireNonNull(expense, "ExpenseRecord cannot be null");

        String sql = "INSERT INTO expenses (category_id, expense_date, amount_paise, payment_mode, note, created_by_user_id) " +
                "VALUES (?, ?, ?, ?, ?, ?);";

        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, expense.categoryId());
            ps.setString(2, expense.expenseDate().toString());
            ps.setLong(3, expense.amount().getPaise());
            ps.setString(4, expense.paymentMode().name());
            ps.setString(5, expense.note() != null ? expense.note().trim() : null);
            ps.setLong(6, expense.createdByUserId());

            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    long id = rs.getLong(1);
                    return new ExpenseRecord(
                            id,
                            expense.categoryId(),
                            expense.categoryName(),
                            expense.expenseDate(),
                            expense.amount(),
                            expense.paymentMode(),
                            expense.note(),
                            null,
                            expense.createdByUserId()
                    );
                }
            }
        }
        throw new DatabaseException("Failed to insert expense record");
    }

    public List<ExpenseRecord> getExpensesByDateRange(Connection conn, LocalDate from, LocalDate to) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        Objects.requireNonNull(from, "From date cannot be null");
        Objects.requireNonNull(to, "To date cannot be null");

        String sql = "SELECT e.id, e.category_id, c.name as category_name, e.expense_date, " +
                "e.amount_paise, e.payment_mode, e.note, e.created_at, e.created_by_user_id " +
                "FROM expenses e JOIN expense_categories c ON e.category_id = c.id " +
                "WHERE e.expense_date BETWEEN ? AND ? ORDER BY e.expense_date DESC, e.id DESC;";

        List<ExpenseRecord> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, from.toString());
            ps.setString(2, to.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapRowToExpense(rs));
                }
            }
        }
        return list;
    }

    public List<ExpenseRecord> getExpensesByDate(Connection conn, LocalDate date) throws SQLException {
        return getExpensesByDateRange(conn, date, date);
    }

    public long getTotalExpensesInPeriod(Connection conn, LocalDate from, LocalDate to) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        StringBuilder sql = new StringBuilder("SELECT COALESCE(SUM(amount_paise), 0) FROM expenses WHERE 1=1 ");
        List<String> params = new ArrayList<>();
        if (from != null) {
            sql.append("AND expense_date >= ? ");
            params.add(from.toString());
        }
        if (to != null) {
            sql.append("AND expense_date <= ? ");
            params.add(to.toString());
        }

        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setString(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        }
        return 0L;
    }

    public long getTotalCashExpensesForDate(Connection conn, LocalDate date) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        Objects.requireNonNull(date, "Date cannot be null");

        String sql = "SELECT COALESCE(SUM(amount_paise), 0) FROM expenses " +
                "WHERE expense_date = ? AND payment_mode = 'CASH';";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, date.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        }
        return 0L;
    }

    private ExpenseRecord mapRowToExpense(ResultSet rs) throws SQLException {
        return new ExpenseRecord(
                rs.getLong("id"),
                rs.getLong("category_id"),
                rs.getString("category_name"),
                LocalDate.parse(rs.getString("expense_date")),
                Money.ofPaise(rs.getLong("amount_paise")),
                PaymentMode.valueOf(rs.getString("payment_mode")),
                rs.getString("note"),
                rs.getString("created_at"),
                rs.getLong("created_by_user_id")
        );
    }
}
