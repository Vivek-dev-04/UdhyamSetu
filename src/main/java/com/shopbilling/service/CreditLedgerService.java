package com.shopbilling.service;

import com.shopbilling.db.CustomerLedgerRepository;
import com.shopbilling.db.CustomerRepository;
import com.shopbilling.db.SaleRepository;
import com.shopbilling.db.SupplierLedgerRepository;
import com.shopbilling.db.SupplierRepository;
import com.shopbilling.db.TransactionHelper;
import com.shopbilling.model.AgingSummary;
import com.shopbilling.model.Customer;
import com.shopbilling.model.CustomerCreditStatement;
import com.shopbilling.model.CustomerDuesSummary;
import com.shopbilling.model.CustomerLedgerEntry;
import com.shopbilling.model.Money;
import com.shopbilling.model.PaymentAllocation;
import com.shopbilling.model.PaymentMode;
import com.shopbilling.model.Sale;
import com.shopbilling.model.SaleAllocationInput;
import com.shopbilling.model.Supplier;
import com.shopbilling.model.SupplierLedgerEntry;
import com.shopbilling.model.SupplierPayableSummary;
import com.shopbilling.model.SupplierStatement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Service managing customer and supplier credit ledgers, payment allocations,
 * and 4-tier aging analysis (0-30, 31-60, 61-90, 90+ days) per PRD Section 7.6, FR-26 - FR-30.
 * Pure service: strictly contains NO JavaFX imports.
 */
public class CreditLedgerService {

    private static final Logger logger = LoggerFactory.getLogger(CreditLedgerService.class);

    private final TransactionHelper transactionHelper;
    private final CustomerLedgerRepository customerLedgerRepository;
    private final SupplierLedgerRepository supplierLedgerRepository;
    private final CustomerRepository customerRepository;
    private final SupplierRepository supplierRepository;
    private final SaleRepository saleRepository;
    private final IstClock clock;

    public CreditLedgerService(TransactionHelper transactionHelper,
                               CustomerLedgerRepository customerLedgerRepository,
                               SupplierLedgerRepository supplierLedgerRepository,
                               CustomerRepository customerRepository,
                               SupplierRepository supplierRepository,
                               SaleRepository saleRepository,
                               IstClock clock) {
        this.transactionHelper = Objects.requireNonNull(transactionHelper, "TransactionHelper cannot be null");
        this.customerLedgerRepository = Objects.requireNonNull(customerLedgerRepository, "CustomerLedgerRepository cannot be null");
        this.supplierLedgerRepository = Objects.requireNonNull(supplierLedgerRepository, "SupplierLedgerRepository cannot be null");
        this.customerRepository = Objects.requireNonNull(customerRepository, "CustomerRepository cannot be null");
        this.supplierRepository = Objects.requireNonNull(supplierRepository, "SupplierRepository cannot be null");
        this.saleRepository = Objects.requireNonNull(saleRepository, "SaleRepository cannot be null");
        this.clock = Objects.requireNonNull(clock, "IstClock cannot be null");
    }

    /**
     * Records a customer payment received, updating customer dues and optionally allocating
     * payment to specific sales (FR-26, FR-27).
     */
    public CustomerLedgerEntry recordCustomerPayment(long customerId,
                                                     Money amount,
                                                     PaymentMode mode,
                                                     String referenceNumber,
                                                     LocalDate paymentDate,
                                                     List<SaleAllocationInput> allocations,
                                                     String notes,
                                                     long userId) {
        Objects.requireNonNull(amount, "Payment amount cannot be null");
        Objects.requireNonNull(mode, "PaymentMode cannot be null");
        if (!amount.isPositive()) {
            throw new ValidationException("Payment amount must be strictly positive: " + amount);
        }

        final LocalDate effectiveDate = paymentDate != null ? paymentDate : clock.today();

        return transactionHelper.execute(conn -> {
            // 1. Verify customer exists
            Customer customer = customerRepository.findById(conn, customerId)
                    .orElseThrow(() -> new EntityNotFoundException("Customer id=" + customerId + " not found"));

            // 2. Validate allocations if provided
            if (allocations != null && !allocations.isEmpty()) {
                Money totalAllocated = Money.ZERO;
                for (SaleAllocationInput alloc : allocations) {
                    Sale sale = saleRepository.findById(conn, alloc.saleId())
                            .orElseThrow(() -> new EntityNotFoundException("Sale id=" + alloc.saleId() + " not found"));

                    if (sale.customerId() == null || sale.customerId() != customerId) {
                        throw new ValidationException(String.format(
                                "Sale id=%d does not belong to customer id=%d (%s)",
                                alloc.saleId(), customerId, customer.name()
                        ));
                    }
                    totalAllocated = totalAllocated.plus(alloc.amount());
                }

                if (totalAllocated.isGreaterThan(amount)) {
                    throw new ValidationException(String.format(
                            "Total allocated amount (%s) cannot exceed payment received (%s)",
                            totalAllocated, amount
                    ));
                }
            }

            // 3. Append payment entry to ledger (negative amount reduces customer dues)
            String noteText = notes != null ? notes : ("Payment received via " + mode);
            if (referenceNumber != null && !referenceNumber.isBlank()) {
                noteText += " [Ref: " + referenceNumber.trim() + "]";
            }

            long entryId = customerLedgerRepository.insertEntry(
                    conn,
                    customerId,
                    "PAYMENT_RECEIVED",
                    -amount.getPaise(),
                    mode.name(),
                    0L,
                    effectiveDate.toString(),
                    noteText,
                    userId
            );

            // 4. Save allocations
            if (allocations != null) {
                for (SaleAllocationInput alloc : allocations) {
                    customerLedgerRepository.insertAllocation(
                            conn,
                            entryId,
                            alloc.saleId(),
                            alloc.amount().getPaise()
                    );
                }
            }

            logger.info("Recorded customer payment: custId={} amount={} mode={} entryId={}",
                    customerId, amount.toRupeesString(), mode, entryId);

            return new CustomerLedgerEntry(
                    entryId,
                    customerId,
                    "PAYMENT_RECEIVED",
                    amount.negate(),
                    mode.name(),
                    0L,
                    effectiveDate,
                    noteText,
                    null,
                    userId
            );
        });
    }

    /**
     * Records a payment made to a distributor/supplier, reducing shop payables (FR-29).
     */
    public SupplierLedgerEntry recordSupplierPayment(long supplierId,
                                                     Money amount,
                                                     PaymentMode mode,
                                                     String referenceNumber,
                                                     LocalDate paymentDate,
                                                     String notes,
                                                     long userId) {
        Objects.requireNonNull(amount, "Payment amount cannot be null");
        Objects.requireNonNull(mode, "PaymentMode cannot be null");
        if (!amount.isPositive()) {
            throw new ValidationException("Payment amount must be strictly positive: " + amount);
        }

        final LocalDate effectiveDate = paymentDate != null ? paymentDate : clock.today();

        return transactionHelper.execute(conn -> {
            supplierRepository.findById(conn, supplierId)
                    .orElseThrow(() -> new EntityNotFoundException("Supplier id=" + supplierId + " not found"));

            String noteText = notes != null ? notes : ("Payment made via " + mode);
            if (referenceNumber != null && !referenceNumber.isBlank()) {
                noteText += " [Ref: " + referenceNumber.trim() + "]";
            }

            long entryId = supplierLedgerRepository.insertEntry(
                    conn,
                    supplierId,
                    "PAYMENT_MADE",
                    -amount.getPaise(),
                    mode.name(),
                    0L,
                    effectiveDate.toString(),
                    noteText,
                    userId
            );

            logger.info("Recorded supplier payment: supplierId={} amount={} mode={} entryId={}",
                    supplierId, amount.toRupeesString(), mode, entryId);

            return new SupplierLedgerEntry(
                    entryId,
                    supplierId,
                    "PAYMENT_MADE",
                    amount.negate(),
                    mode.name(),
                    0L,
                    effectiveDate,
                    noteText,
                    null,
                    userId
            );
        });
    }

    /**
     * Generates a full customer credit statement with FIFO aging breakdown (FR-26, FR-28).
     */
    public CustomerCreditStatement getCustomerStatement(long customerId, LocalDate asOfDate) {
        final LocalDate targetDate = asOfDate != null ? asOfDate : clock.today();

        return transactionHelper.executeRead(conn -> {
            Customer customer = customerRepository.findById(conn, customerId)
                    .orElseThrow(() -> new EntityNotFoundException("Customer id=" + customerId + " not found"));

            List<CustomerLedgerEntry> entries = customerLedgerRepository.getEntriesForCustomer(conn, customerId);

            long totalDuesPaise = customerLedgerRepository.getCurrentDuesPaise(conn, customerId);
            Money currentDues = Money.ofPaise(totalDuesPaise);

            // Compute available credit
            Money creditLimit = customer.creditLimit();
            Money availableCredit = null;
            if (creditLimit != null) {
                availableCredit = creditLimit.minus(currentDues);
            }

            // Compute FIFO Aging
            AgingSummary aging = calculateFifoAging(entries, targetDate, totalDuesPaise);

            return new CustomerCreditStatement(
                    customer,
                    targetDate,
                    currentDues,
                    creditLimit,
                    availableCredit,
                    aging,
                    entries
            );
        });
    }

    /**
     * Generates a full supplier statement with FIFO aging breakdown (FR-29).
     */
    public SupplierStatement getSupplierStatement(long supplierId, LocalDate asOfDate) {
        final LocalDate targetDate = asOfDate != null ? asOfDate : clock.today();

        return transactionHelper.executeRead(conn -> {
            Supplier supplier = supplierRepository.findById(conn, supplierId)
                    .orElseThrow(() -> new EntityNotFoundException("Supplier id=" + supplierId + " not found"));

            List<SupplierLedgerEntry> entries = supplierLedgerRepository.getEntriesForSupplier(conn, supplierId);

            long totalPayablePaise = supplierLedgerRepository.getCurrentPayablePaise(conn, supplierId);
            Money currentPayable = Money.ofPaise(totalPayablePaise);

            // Adapt supplier entries to common aging calculator
            List<LedgerAgingItem> agingItems = entries.stream()
                    .map(e -> new LedgerAgingItem(e.entryDate(), e.amount().getPaise()))
                    .toList();

            AgingSummary aging = calculateFifoAgingFromItems(agingItems, targetDate, totalPayablePaise);

            return new SupplierStatement(
                    supplier,
                    targetDate,
                    currentPayable,
                    aging,
                    entries
            );
        });
    }

    /**
     * Lists all customers with outstanding credit dues.
     */
    public List<CustomerDuesSummary> getAllCustomersWithDues() {
        return transactionHelper.executeRead(customerLedgerRepository::getAllCustomersWithDues);
    }

    /**
     * Lists all suppliers with outstanding payables.
     */
    public List<SupplierPayableSummary> getAllSuppliersWithPayables() {
        return transactionHelper.executeRead(supplierLedgerRepository::getAllSuppliersWithPayables);
    }

    public Money getCustomerCurrentDues(long customerId) {
        return transactionHelper.executeRead(conn ->
                Money.ofPaise(customerLedgerRepository.getCurrentDuesPaise(conn, customerId)));
    }

    public Money getSupplierCurrentPayable(long supplierId) {
        return transactionHelper.executeRead(conn ->
                Money.ofPaise(supplierLedgerRepository.getCurrentPayablePaise(conn, supplierId)));
    }

    private AgingSummary calculateFifoAging(List<CustomerLedgerEntry> entries, LocalDate asOfDate, long currentDuesPaise) {
        List<LedgerAgingItem> items = entries.stream()
                .map(e -> new LedgerAgingItem(e.entryDate(), e.amount().getPaise()))
                .toList();
        return calculateFifoAgingFromItems(items, asOfDate, currentDuesPaise);
    }

    private record LedgerAgingItem(LocalDate date, long amountPaise) {}

    private AgingSummary calculateFifoAgingFromItems(List<LedgerAgingItem> items, LocalDate asOfDate, long currentDuesPaise) {
        if (currentDuesPaise <= 0) {
            return AgingSummary.zero();
        }

        // Sum payments and credits returned (negative entries)
        long totalPaymentsPaise = 0L;
        List<LedgerAgingItem> debits = new ArrayList<>();

        for (LedgerAgingItem item : items) {
            if (item.amountPaise() > 0) {
                debits.add(item);
            } else {
                totalPaymentsPaise += Math.abs(item.amountPaise());
            }
        }

        // FIFO: Apply payments against the oldest debits first
        long remainingPayments = totalPaymentsPaise;
        long bucket0To30 = 0L;
        long bucket31To60 = 0L;
        long bucket61To90 = 0L;
        long bucket90Plus = 0L;

        for (LedgerAgingItem debit : debits) {
            long debitAmount = debit.amountPaise();
            if (remainingPayments >= debitAmount) {
                remainingPayments -= debitAmount;
                continue; // Fully covered by past payments
            }

            long unpaidPortion = debitAmount - remainingPayments;
            remainingPayments = 0L;

            long daysBetween = ChronoUnit.DAYS.between(debit.date(), asOfDate);
            if (daysBetween < 0) {
                daysBetween = 0;
            }

            if (daysBetween <= 30) {
                bucket0To30 += unpaidPortion;
            } else if (daysBetween <= 60) {
                bucket31To60 += unpaidPortion;
            } else if (daysBetween <= 90) {
                bucket61To90 += unpaidPortion;
            } else {
                bucket90Plus += unpaidPortion;
            }
        }

        return new AgingSummary(
                Money.ofPaise(currentDuesPaise),
                Money.ofPaise(bucket0To30),
                Money.ofPaise(bucket31To60),
                Money.ofPaise(bucket61To90),
                Money.ofPaise(bucket90Plus)
        );
    }
}
