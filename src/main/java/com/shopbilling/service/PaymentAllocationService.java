package com.shopbilling.service;

import com.shopbilling.db.CustomerLedgerRepository;
import com.shopbilling.db.CustomerRepository;
import com.shopbilling.db.SaleRepository;
import com.shopbilling.db.TransactionHelper;
import com.shopbilling.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Service managing customer outstanding credit invoices and payment allocations
 * (PRD Section 7.6, FR-26, FR-27).
 * Pure service: strictly contains NO JavaFX imports.
 */
public class PaymentAllocationService {

    private static final Logger logger = LoggerFactory.getLogger(PaymentAllocationService.class);

    private final TransactionHelper transactionHelper;
    private final CustomerRepository customerRepository;
    private final CustomerLedgerRepository customerLedgerRepository;
    private final SaleRepository saleRepository;

    public PaymentAllocationService(TransactionHelper transactionHelper,
                                  CustomerRepository customerRepository,
                                  CustomerLedgerRepository customerLedgerRepository,
                                  SaleRepository saleRepository) {
        this.transactionHelper = Objects.requireNonNull(transactionHelper, "TransactionHelper cannot be null");
        this.customerRepository = Objects.requireNonNull(customerRepository, "CustomerRepository cannot be null");
        this.customerLedgerRepository = Objects.requireNonNull(customerLedgerRepository, "CustomerLedgerRepository cannot be null");
        this.saleRepository = Objects.requireNonNull(saleRepository, "SaleRepository cannot be null");
    }

    /**
     * Retrieves all credit sales for a customer, calculating total credit granted,
     * amount already allocated from past payments, and remaining unpaid balance (FR-26, FR-27).
     *
     * @param customerId The customer ID.
     * @param unpaidOnly If true, returns only sales with remaining unpaid credit dues.
     * @return List of outstanding sale items ordered chronologically.
     */
    public List<OutstandingSaleItem> getCreditSalesStatus(long customerId, boolean unpaidOnly) {
        return transactionHelper.executeRead(conn -> {
            customerRepository.findById(conn, customerId)
                    .orElseThrow(() -> new EntityNotFoundException("Customer id=" + customerId + " not found"));

            String sql = "SELECT s.id, s.bill_number, s.sale_date, s.total_amount_paise, " +
                    "COALESCE(p.credit_paise, 0) AS credit_paise, " +
                    "COALESCE(a.allocated_paise, 0) AS allocated_paise " +
                    "FROM sales s " +
                    "JOIN (" +
                    "    SELECT sale_id, SUM(amount_paise) AS credit_paise " +
                    "    FROM sale_payments " +
                    "    WHERE payment_mode = 'CREDIT' " +
                    "    GROUP BY sale_id" +
                    ") p ON s.id = p.sale_id " +
                    "LEFT JOIN (" +
                    "    SELECT sale_id, SUM(allocated_amount_paise) AS allocated_paise " +
                    "    FROM customer_payment_allocations " +
                    "    GROUP BY sale_id" +
                    ") a ON s.id = a.sale_id " +
                    "WHERE s.customer_id = ? " +
                    "ORDER BY s.sale_date ASC, s.id ASC;";

            List<OutstandingSaleItem> results = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, customerId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long saleId = rs.getLong("id");
                        String billNumber = rs.getString("bill_number");
                        LocalDate saleDate = LocalDate.parse(rs.getString("sale_date"));
                        Money totalAmount = Money.ofPaise(rs.getLong("total_amount_paise"));
                        Money creditAmount = Money.ofPaise(rs.getLong("credit_paise"));
                        Money allocatedAmount = Money.ofPaise(rs.getLong("allocated_paise"));

                        Money balance = creditAmount.minus(allocatedAmount);
                        if (balance.isNegative()) {
                            balance = Money.ZERO;
                        }

                        boolean fullyPaid = balance.isZero();
                        if (!unpaidOnly || !fullyPaid) {
                            results.add(new OutstandingSaleItem(
                                    saleId,
                                    billNumber != null ? billNumber : ("SALE #" + saleId),
                                    saleDate,
                                    totalAmount,
                                    creditAmount,
                                    allocatedAmount,
                                    balance,
                                    fullyPaid
                            ));
                        }
                    }
                }
            }
            return results;
        });
    }

    /**
     * Computes automated FIFO allocations for a received customer payment, allocating the payment
     * to the oldest unpaid credit sales first (FR-27).
     *
     * @param customerId The customer making the payment.
     * @param paymentAmount The amount of payment received.
     * @return Ordered list of SaleAllocationInput records ready for recording in the ledger.
     */
    public List<SaleAllocationInput> computeFifoAllocations(long customerId, Money paymentAmount) {
        Objects.requireNonNull(paymentAmount, "Payment amount cannot be null");
        if (!paymentAmount.isPositive()) {
            throw new ValidationException("Payment amount must be strictly positive: " + paymentAmount);
        }

        List<OutstandingSaleItem> unpaidSales = getCreditSalesStatus(customerId, true);
        List<SaleAllocationInput> allocations = new ArrayList<>();

        Money remainingPayment = paymentAmount;
        for (OutstandingSaleItem sale : unpaidSales) {
            if (remainingPayment.isZero()) {
                break;
            }

            Money toAllocate = remainingPayment.isGreaterThan(sale.outstandingBalance())
                    ? sale.outstandingBalance()
                    : remainingPayment;

            allocations.add(new SaleAllocationInput(sale.saleId(), toAllocate));
            remainingPayment = remainingPayment.minus(toAllocate);
        }

        logger.info("Computed FIFO allocations for custId={} payment={}: allocated to {} sale(s), unallocated remainder={}",
                customerId, paymentAmount.toRupeesString(), allocations.size(), remainingPayment.toRupeesString());

        return allocations;
    }

    /**
     * Retrieves all payment allocations that have been applied to a given sale.
     */
    public List<PaymentAllocation> getAllocationsForSale(long saleId) {
        return transactionHelper.executeRead(conn -> {
            saleRepository.findById(conn, saleId)
                    .orElseThrow(() -> new EntityNotFoundException("Sale id=" + saleId + " not found"));

            String sql = "SELECT id, payment_ledger_entry_id, sale_id, allocated_amount_paise, created_at " +
                    "FROM customer_payment_allocations WHERE sale_id = ? ORDER BY id ASC;";

            List<PaymentAllocation> list = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, saleId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        list.add(new PaymentAllocation(
                                rs.getLong("id"),
                                rs.getLong("payment_ledger_entry_id"),
                                rs.getLong("sale_id"),
                                Money.ofPaise(rs.getLong("allocated_amount_paise")),
                                rs.getString("created_at")
                        ));
                    }
                }
            }
            return list;
        });
    }
}
