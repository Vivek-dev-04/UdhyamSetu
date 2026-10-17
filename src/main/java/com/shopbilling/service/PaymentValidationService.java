package com.shopbilling.service;

import com.shopbilling.db.CustomerRepository;
import com.shopbilling.db.TransactionHelper;
import com.shopbilling.model.Customer;
import com.shopbilling.model.Money;
import com.shopbilling.model.PaymentSplit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

/**
 * Service for validating split payments and credit eligibility (PRD Section 7.3, FR-11 & FR-12).
 * Strictly contains NO JavaFX imports.
 */
public class PaymentValidationService {

    private static final Logger logger = LoggerFactory.getLogger(PaymentValidationService.class);

    private final TransactionHelper transactionHelper;
    private final CustomerRepository customerRepository;

    public PaymentValidationService(TransactionHelper transactionHelper, CustomerRepository customerRepository) {
        this.transactionHelper = Objects.requireNonNull(transactionHelper, "TransactionHelper cannot be null");
        this.customerRepository = Objects.requireNonNull(customerRepository, "CustomerRepository cannot be null");
    }

    /**
     * Validates that:
     * 1. Split payments match the sale total exactly.
     * 2. If credit is used, a valid customer is associated.
     * 3. If the customer has a credit limit, the credit does not breach the limit.
     */
    public void validatePayments(Connection conn, PaymentSplit split, Money saleTotal, Long customerId) throws SQLException {
        Objects.requireNonNull(split, "PaymentSplit cannot be null");
        Objects.requireNonNull(saleTotal, "Sale total cannot be null");

        // 1. Validate payment sum matches sale total
        split.validateAgainstTotal(saleTotal);

        // 2. Validate credit eligibility
        if (split.hasCredit()) {
            if (customerId == null) {
                throw new ValidationException("Credit sale requires an identified customer. Walk-in sales cannot be given credit.");
            }

            Customer customer = customerRepository.findById(conn, customerId)
                    .orElseThrow(() -> new EntityNotFoundException("Customer id=" + customerId + " not found"));

            Money creditAmount = split.creditTotal();

            // Check customer credit limit if configured
            if (customer.creditLimit() != null) {
                // Calculate current dues
                long currentDuesPaise = getCurrentCustomerDuesPaise(conn, customerId);
                Money currentDues = Money.ofPaise(currentDuesPaise);
                Money projectedDues = currentDues.plus(creditAmount);

                if (projectedDues.isGreaterThan(customer.creditLimit())) {
                    throw new ValidationException(String.format(
                            "Credit limit exceeded for customer '%s'. Credit limit: %s, Current dues: %s, Attempted credit: %s (Total would be %s)",
                            customer.name(), customer.creditLimit().toFormattedString(),
                            currentDues.toFormattedString(), creditAmount.toFormattedString(), projectedDues.toFormattedString()
                    ));
                }
            }

            logger.info("Credit sale approved for customer '{}' id={} amount={}",
                    customer.name(), customerId, creditAmount);
        }
    }

    private long getCurrentCustomerDuesPaise(Connection conn, long customerId) throws SQLException {
        String sql = "SELECT COALESCE(SUM(amount_paise), 0) FROM customer_ledger_entries WHERE customer_id = ?;";
        try (var ps = conn.prepareStatement(sql)) {
            ps.setLong(1, customerId);
            try (var rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        }
        return 0L;
    }
}
