package com.shopbilling.service;

import com.shopbilling.db.ProductVariantRepository;
import com.shopbilling.db.SettingsRepository;
import com.shopbilling.db.StockRepository;
import com.shopbilling.db.TransactionHelper;
import com.shopbilling.model.CurrentStock;
import com.shopbilling.model.Quantity;
import com.shopbilling.model.StockMovement;
import com.shopbilling.model.StockMovementType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Service managing stock ledger, derived balances, negative stock enforcement,
 * and low stock alerts (PRD Section 7.4, FR-15, FR-17, FR-18, FR-19).
 * Strictly contains NO JavaFX imports.
 */
public class StockService {

    private static final Logger logger = LoggerFactory.getLogger(StockService.class);

    public static final String SETTING_ALLOW_NEGATIVE_STOCK = "ALLOW_NEGATIVE_STOCK";

    private final TransactionHelper transactionHelper;
    private final StockRepository stockRepository;
    private final SettingsRepository settingsRepository;
    private final ProductVariantRepository variantRepository;

    public StockService(TransactionHelper transactionHelper,
                        StockRepository stockRepository,
                        SettingsRepository settingsRepository,
                        ProductVariantRepository variantRepository) {
        this.transactionHelper = Objects.requireNonNull(transactionHelper, "TransactionHelper cannot be null");
        this.stockRepository = Objects.requireNonNull(stockRepository, "StockRepository cannot be null");
        this.settingsRepository = Objects.requireNonNull(settingsRepository, "SettingsRepository cannot be null");
        this.variantRepository = Objects.requireNonNull(variantRepository, "ProductVariantRepository cannot be null");
    }

    /**
     * Records a stock movement within an existing transaction connection.
     * Enforces negative stock settings if deducting stock.
     */
    public StockMovement recordMovementInTransaction(Connection conn,
                                                      long variantId,
                                                      StockMovementType movementType,
                                                      Quantity deltaQuantity,
                                                      String referenceType,
                                                      long referenceId,
                                                      String reason,
                                                      long userId) throws SQLException {
        Objects.requireNonNull(conn, "Connection cannot be null");
        Objects.requireNonNull(movementType, "StockMovementType cannot be null");
        Objects.requireNonNull(deltaQuantity, "Delta quantity cannot be null");

        // Validate variant exists
        variantRepository.findById(conn, variantId)
                .orElseThrow(() -> new EntityNotFoundException("ProductVariant id=" + variantId + " not found"));

        if (deltaQuantity.isZero()) {
            throw new ValidationException("Stock movement quantity cannot be zero");
        }

        // If deducting stock, check negative stock setting
        if (deltaQuantity.isNegative()) {
            long currentStockScaled = stockRepository.getCurrentStockScaled(conn, variantId);
            long newStockScaled = currentStockScaled + deltaQuantity.getScaledValue();

            if (newStockScaled < 0) {
                boolean allowNegative = Boolean.parseBoolean(
                        settingsRepository.getSettingOrDefault(conn, SETTING_ALLOW_NEGATIVE_STOCK, "false")
                );

                if (!allowNegative) {
                    Quantity current = Quantity.ofScaled(currentStockScaled);
                    Quantity requestedDeduction = deltaQuantity.negate();
                    throw new ValidationException(String.format(
                            "Negative stock is not permitted. Current stock: %s, requested deduction: %s (variant id=%d)",
                            current.toDisplayString(), requestedDeduction.toDisplayString(), variantId
                    ));
                }
            }
        }

        StockMovement movement = new StockMovement(
                null,
                variantId,
                movementType,
                deltaQuantity,
                referenceType,
                referenceId,
                reason,
                null,
                userId
        );

        StockMovement saved = stockRepository.insertMovement(conn, movement);
        logger.info("Recorded stock movement id={} variantId={} type={} qty={} ref={}:{}",
                saved.id(), variantId, movementType, deltaQuantity.toDisplayString(), referenceType, referenceId);
        return saved;
    }

    /**
     * Standalone method to record a stock movement in its own transaction.
     */
    public StockMovement recordMovement(long variantId,
                                        StockMovementType movementType,
                                        Quantity deltaQuantity,
                                        String referenceType,
                                        long referenceId,
                                        String reason,
                                        long userId) {
        return transactionHelper.execute(conn ->
                recordMovementInTransaction(conn, variantId, movementType, deltaQuantity, referenceType, referenceId, reason, userId)
        );
    }

    /**
     * Performs a manual stock adjustment by computing the required delta (FR-17).
     */
    public StockMovement adjustStock(long variantId, Quantity targetStock, String reason, long userId) {
        Objects.requireNonNull(targetStock, "Target stock cannot be null");
        if (reason == null || reason.trim().isEmpty()) {
            throw new ValidationException("Reason is required for manual stock adjustment");
        }

        return transactionHelper.execute(conn -> {
            long currentScaled = stockRepository.getCurrentStockScaled(conn, variantId);
            Quantity current = Quantity.ofScaled(currentScaled);
            Quantity delta = targetStock.minus(current);

            if (delta.isZero()) {
                throw new ValidationException("Target stock matches current stock (" + targetStock.toDisplayString() + ")");
            }

            return recordMovementInTransaction(
                    conn,
                    variantId,
                    StockMovementType.ADJUSTMENT,
                    delta,
                    "ADJUSTMENT",
                    0L,
                    reason.trim(),
                    userId
            );
        });
    }

    public Quantity getCurrentStock(long variantId) {
        return transactionHelper.executeRead(conn ->
                Quantity.ofScaled(stockRepository.getCurrentStockScaled(conn, variantId))
        );
    }

    public List<StockMovement> getMovements(long variantId) {
        return transactionHelper.executeRead(conn ->
                stockRepository.getMovementsForVariant(conn, variantId)
        );
    }

    public List<CurrentStock> getAllCurrentStock() {
        return transactionHelper.executeRead(stockRepository::getAllCurrentStock);
    }

    public List<CurrentStock> getLowStockItems() {
        return getAllCurrentStock().stream()
                .filter(CurrentStock::isLowStock)
                .collect(Collectors.toList());
    }

    public boolean isNegativeStockAllowed() {
        return transactionHelper.executeRead(conn ->
                Boolean.parseBoolean(settingsRepository.getSettingOrDefault(conn, SETTING_ALLOW_NEGATIVE_STOCK, "false"))
        );
    }

    public void setAllowNegativeStock(boolean allow, long userId) {
        transactionHelper.executeVoid(conn ->
                settingsRepository.setSetting(conn, SETTING_ALLOW_NEGATIVE_STOCK, String.valueOf(allow), userId)
        );
        logger.info("Setting ALLOW_NEGATIVE_STOCK updated to: {} by userId={}", allow, userId);
    }
}
