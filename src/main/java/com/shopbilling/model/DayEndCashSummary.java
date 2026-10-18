package com.shopbilling.model;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Breakdown of expected cash for a specific business day (PRD Section 7.9, FR-38).
 * Expected cash = cash sales - cash refunds + cash customer payments received - cash expenses.
 */
public record DayEndCashSummary(
        LocalDate date,
        Money cashSales,
        Money cashRefunds,
        Money cashCustomerPayments,
        Money cashExpenses,
        Money expectedCash
) {
    public DayEndCashSummary {
        Objects.requireNonNull(date, "Date cannot be null");
        Objects.requireNonNull(cashSales, "Cash sales cannot be null");
        Objects.requireNonNull(cashRefunds, "Cash refunds cannot be null");
        Objects.requireNonNull(cashCustomerPayments, "Cash customer payments cannot be null");
        Objects.requireNonNull(cashExpenses, "Cash expenses cannot be null");
        Objects.requireNonNull(expectedCash, "Expected cash cannot be null");
    }

    public static DayEndCashSummary of(LocalDate date,
                                       Money cashSales,
                                       Money cashRefunds,
                                       Money cashCustomerPayments,
                                       Money cashExpenses) {
        Money expected = cashSales.minus(cashRefunds).plus(cashCustomerPayments).minus(cashExpenses);
        return new DayEndCashSummary(date, cashSales, cashRefunds, cashCustomerPayments, cashExpenses, expected);
    }
}
