package com.shopbilling.service;

import com.shopbilling.db.ProductVariantRepository;
import com.shopbilling.db.StockRepository;
import com.shopbilling.db.TransactionHelper;
import com.shopbilling.db.UserRepository;
import com.shopbilling.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Service managing manual stock adjustments, damage write-offs, and physical stock reconciliation
 * (PRD Section 5, Section 7.4 FR-17, Section 7.5 FR-22, Section 7.10 FR-44).
 * Pure service: strictly contains NO JavaFX imports.
 */
public class StockAdjustmentService {

    private static final Logger logger = LoggerFactory.getLogger(StockAdjustmentService.class);

    private final TransactionHelper transactionHelper;
    private final StockRepository stockRepository;
    private final ProductVariantRepository variantRepository;
    private final StockService stockService;
    private final UserRepository userRepository;
    private final AuditLogService auditLogService;

    public StockAdjustmentService(TransactionHelper transactionHelper,
                                  StockRepository stockRepository,
                                  ProductVariantRepository variantRepository,
                                  StockService stockService,
                                  UserRepository userRepository,
                                  AuditLogService auditLogService) {
        this.transactionHelper = Objects.requireNonNull(transactionHelper, "TransactionHelper cannot be null");
        this.stockRepository = Objects.requireNonNull(stockRepository, "StockRepository cannot be null");
        this.variantRepository = Objects.requireNonNull(variantRepository, "ProductVariantRepository cannot be null");
        this.stockService = Objects.requireNonNull(stockService, "StockService cannot be null");
        this.userRepository = Objects.requireNonNull(userRepository, "UserRepository cannot be null");
        this.auditLogService = Objects.requireNonNull(auditLogService, "AuditLogService cannot be null");
    }

    /**
     * Manually adjusts stock to a target quantity. Enforces Owner permission and writes audit log (FR-17, FR-44).
     */
    public StockAdjustmentResult adjustStock(long requestingUserId, long variantId, Quantity targetStock, String reason) {
        Objects.requireNonNull(targetStock, "Target stock cannot be null");
        if (reason == null || reason.trim().isEmpty()) {
            throw new ValidationException("Reason is required for manual stock adjustment");
        }

        return transactionHelper.execute(conn -> {
            verifyOwner(conn, requestingUserId);

            ProductVariant variant = variantRepository.findById(conn, variantId)
                    .orElseThrow(() -> new EntityNotFoundException("ProductVariant id=" + variantId + " not found"));

            long currentScaled = stockRepository.getCurrentStockScaled(conn, variantId);
            Quantity currentStock = Quantity.ofScaled(currentScaled);
            Quantity delta = targetStock.minus(currentStock);

            if (delta.isZero()) {
                throw new ValidationException("Target stock matches current stock (" + targetStock.toDisplayString() + ")");
            }

            StockMovement movement = stockService.recordMovementInTransaction(
                    conn,
                    variantId,
                    StockMovementType.ADJUSTMENT,
                    delta,
                    "ADJUSTMENT",
                    0L,
                    reason.trim(),
                    requestingUserId
            );

            auditLogService.logEventInTransaction(
                    conn,
                    requestingUserId,
                    "STOCK_ADJUSTMENT",
                    "STOCK",
                    String.valueOf(variantId),
                    currentStock.toDisplayString(),
                    targetStock.toDisplayString(),
                    reason.trim()
            );

            logger.info("Stock adjusted for variantId={} from {} to {} (delta={}) by userId={}",
                    variantId, currentStock.toDisplayString(), targetStock.toDisplayString(), delta.toDisplayString(), requestingUserId);

            return new StockAdjustmentResult(
                    variantId,
                    variant.variantName(),
                    currentStock,
                    targetStock,
                    delta,
                    StockMovementType.ADJUSTMENT,
                    reason.trim(),
                    movement.id() != null ? movement.id() : 0L
            );
        });
    }

    /**
     * Writes off stock into the damaged quarantine bucket, deducting it from sellable stock (FR-15, FR-22).
     */
    public StockAdjustmentResult writeOffDamagedStock(long requestingUserId, long variantId, Quantity damagedQuantity, String reason) {
        Objects.requireNonNull(damagedQuantity, "Damaged quantity cannot be null");
        if (!damagedQuantity.isPositive()) {
            throw new ValidationException("Damaged quantity must be strictly positive: " + damagedQuantity);
        }
        if (reason == null || reason.trim().isEmpty()) {
            throw new ValidationException("Reason is required for damaged stock write-off");
        }

        return transactionHelper.execute(conn -> {
            verifyOwner(conn, requestingUserId);

            ProductVariant variant = variantRepository.findById(conn, variantId)
                    .orElseThrow(() -> new EntityNotFoundException("ProductVariant id=" + variantId + " not found"));

            long currentScaled = stockRepository.getCurrentStockScaled(conn, variantId);
            Quantity currentStock = Quantity.ofScaled(currentScaled);
            Quantity newStock = currentStock.minus(damagedQuantity);

            StockMovement movement = stockService.recordMovementInTransaction(
                    conn,
                    variantId,
                    StockMovementType.DAMAGE,
                    damagedQuantity.negate(), // Deduct from sellable stock
                    "DAMAGE",
                    0L,
                    reason.trim(),
                    requestingUserId
            );

            auditLogService.logEventInTransaction(
                    conn,
                    requestingUserId,
                    "STOCK_DAMAGE",
                    "STOCK",
                    String.valueOf(variantId),
                    currentStock.toDisplayString(),
                    newStock.toDisplayString(),
                    reason.trim()
            );

            logger.info("Damaged stock recorded for variantId={}: qty={} reason='{}' by userId={}",
                    variantId, damagedQuantity.toDisplayString(), reason.trim(), requestingUserId);

            return new StockAdjustmentResult(
                    variantId,
                    variant.variantName(),
                    currentStock,
                    newStock,
                    damagedQuantity.negate(),
                    StockMovementType.DAMAGE,
                    reason.trim(),
                    movement.id() != null ? movement.id() : 0L
            );
        });
    }

    /**
     * Reconciles physical stock counts against system stock in ONE atomic transaction (PRD Section 13).
     */
    public PhysicalReconciliationSummary reconcilePhysicalInventory(long requestingUserId,
                                                                    List<PhysicalCountItemInput> counts,
                                                                    String reconciliationNote) {
        if (counts == null || counts.isEmpty()) {
            throw new ValidationException("Physical counts list cannot be empty");
        }

        final String note = reconciliationNote != null && !reconciliationNote.trim().isEmpty()
                ? reconciliationNote.trim()
                : "Physical inventory audit reconciliation";

        return transactionHelper.execute(conn -> {
            verifyOwner(conn, requestingUserId);

            List<StockAdjustmentResult> adjustments = new ArrayList<>();
            int matched = 0;

            for (PhysicalCountItemInput countInput : counts) {
                ProductVariant variant = variantRepository.findById(conn, countInput.variantId())
                        .orElseThrow(() -> new EntityNotFoundException("ProductVariant id=" + countInput.variantId() + " not found"));

                long currentScaled = stockRepository.getCurrentStockScaled(conn, countInput.variantId());
                Quantity currentStock = Quantity.ofScaled(currentScaled);
                Quantity countedStock = countInput.countedQuantity();

                Quantity delta = countedStock.minus(currentStock);
                if (delta.isZero()) {
                    matched++;
                } else {
                    StockMovement movement = stockService.recordMovementInTransaction(
                            conn,
                            variant.id(),
                            StockMovementType.ADJUSTMENT,
                            delta,
                            "ADJUSTMENT",
                            0L,
                            note,
                            requestingUserId
                    );

                    auditLogService.logEventInTransaction(
                            conn,
                            requestingUserId,
                            "STOCK_ADJUSTMENT",
                            "STOCK",
                            String.valueOf(variant.id()),
                            currentStock.toDisplayString(),
                            countedStock.toDisplayString(),
                            note + " (variance: " + delta.toDisplayString() + ")"
                    );

                    adjustments.add(new StockAdjustmentResult(
                            variant.id(),
                            variant.variantName(),
                            currentStock,
                            countedStock,
                            delta,
                            StockMovementType.ADJUSTMENT,
                            note,
                            movement.id() != null ? movement.id() : 0L
                    ));
                }
            }

            logger.info("Physical stock reconciliation completed: total={} matched={} adjusted={}",
                    counts.size(), matched, adjustments.size());

            return new PhysicalReconciliationSummary(
                    counts.size(),
                    adjustments.size(),
                    matched,
                    adjustments,
                    note
            );
        });
    }

    private void verifyOwner(Connection conn, long userId) throws SQLException {
        User user = userRepository.findById(conn, userId)
                .orElseThrow(() -> new EntityNotFoundException("User id=" + userId + " not found"));
        if (!user.isOwner()) {
            throw new UnauthorizedException("Only the shop owner is authorized to perform stock adjustments");
        }
    }
}
