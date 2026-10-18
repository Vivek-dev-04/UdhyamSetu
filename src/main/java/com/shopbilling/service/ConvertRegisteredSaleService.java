package com.shopbilling.service;

import com.shopbilling.db.PaymentRepository;
import com.shopbilling.db.SaleRepository;
import com.shopbilling.db.TransactionHelper;
import com.shopbilling.db.UserRepository;
import com.shopbilling.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

/**
 * Service to convert an existing unbilled registered counter sale into an official GST invoice
 * (PRD Section 6 Rules, FR-1, FR-44).
 * Strictly contains NO JavaFX imports.
 */
public class ConvertRegisteredSaleService {

    private static final Logger logger = LoggerFactory.getLogger(ConvertRegisteredSaleService.class);

    private final TransactionHelper transactionHelper;
    private final SaleRepository saleRepository;
    private final PaymentRepository paymentRepository;
    private final BillCounterService billCounterService;
    private final UserRepository userRepository;
    private final AuditLogService auditLogService;
    private final IstClock clock;

    public ConvertRegisteredSaleService(TransactionHelper transactionHelper,
                                        SaleRepository saleRepository,
                                        PaymentRepository paymentRepository,
                                        BillCounterService billCounterService,
                                        UserRepository userRepository,
                                        AuditLogService auditLogService,
                                        IstClock clock) {
        this.transactionHelper = Objects.requireNonNull(transactionHelper, "TransactionHelper cannot be null");
        this.saleRepository = Objects.requireNonNull(saleRepository, "SaleRepository cannot be null");
        this.paymentRepository = Objects.requireNonNull(paymentRepository, "PaymentRepository cannot be null");
        this.billCounterService = Objects.requireNonNull(billCounterService, "BillCounterService cannot be null");
        this.userRepository = Objects.requireNonNull(userRepository, "UserRepository cannot be null");
        this.auditLogService = Objects.requireNonNull(auditLogService, "AuditLogService cannot be null");
        this.clock = Objects.requireNonNull(clock, "IstClock cannot be null");
    }

    /**
     * Converts an existing registered sale into a billed invoice by assigning it the next
     * sequential bill number in the active financial year (PRD Section 6 Rules).
     *
     * @param requestingUserId The owner user initiating the conversion
     * @param saleId The ID of the existing registered sale
     * @return Updated Sale record with billNumber assigned
     */
    public Sale convertRegisteredSaleToBill(long requestingUserId, long saleId) {
        return transactionHelper.execute(conn -> {
            // 1. Verify owner authorization
            verifyOwner(conn, requestingUserId);

            // 2. Fetch existing sale
            Sale existing = saleRepository.findById(conn, saleId)
                    .orElseThrow(() -> new EntityNotFoundException("Sale id=" + saleId + " not found"));

            // 3. Validation: Sale must be currently unbilled (REGISTERED)
            if (existing.isBilled() || existing.billNumber() != null) {
                throw new ValidationException(String.format(
                        "Sale id=%d is already billed with invoice number '%s'", saleId, existing.billNumber()
                ));
            }

            if (existing.saleMode() != SaleMode.REGISTERED) {
                throw new ValidationException(String.format(
                        "Sale id=%d has sale mode '%s' and cannot be converted", saleId, existing.saleMode()
                ));
            }

            // 4. Determine current financial year at conversion time
            FinancialYear fy = FinancialYear.ofDate(clock.today());

            // 5. Atomically reserve next sequential bill number (gap-free)
            BillNumber billNumber = billCounterService.nextBillNumberInTransaction(conn, fy);

            // 6. Assign bill number and update sale mode to BILL in database
            saleRepository.assignBillNumber(conn, saleId, billNumber.formattedNumber(), (int) billNumber.sequenceNumber());

            // 7. Record append-only audit event (FR-44)
            auditLogService.logEventInTransaction(
                    conn,
                    requestingUserId,
                    "CONVERT_REGISTERED_TO_BILL",
                    "SALE",
                    String.valueOf(saleId),
                    "REGISTERED",
                    billNumber.formattedNumber(),
                    "Registered counter sale converted to official invoice"
            );

            logger.info("Successfully converted registered sale id={} to invoice '{}' in FY {}",
                    saleId, billNumber.formattedNumber(), fy.getLabel());

            // 8. Fetch line items and payments to return complete Sale domain object
            List<SaleItem> items = saleRepository.findItemsBySaleId(conn, saleId);
            List<PaymentRecord> payments = paymentRepository.findBySaleId(conn, saleId);

            return new Sale(
                    existing.id(),
                    SaleMode.BILL,
                    fy.getLabel(),
                    billNumber.formattedNumber(),
                    (int) billNumber.sequenceNumber(),
                    existing.customerId(),
                    existing.saleDate(),
                    existing.createdAt(),
                    existing.subtotal(),
                    existing.discount(),
                    existing.totalAmount(),
                    existing.createdByUserId(),
                    items,
                    payments
            );
        });
    }

    private void verifyOwner(Connection conn, long userId) throws SQLException {
        User user = userRepository.findById(conn, userId)
                .orElseThrow(() -> new EntityNotFoundException("User id=" + userId + " not found"));
        if (!user.isOwner()) {
            throw new UnauthorizedException("Only the shop owner is authorized to convert registered sales to bills");
        }
    }
}
