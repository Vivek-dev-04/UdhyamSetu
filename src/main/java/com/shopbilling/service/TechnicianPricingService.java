package com.shopbilling.service;

import com.shopbilling.db.CustomerRepository;
import com.shopbilling.db.ProductVariantRepository;
import com.shopbilling.db.ProfitRepository;
import com.shopbilling.db.SettingsRepository;
import com.shopbilling.db.TransactionHelper;
import com.shopbilling.db.UserRepository;
import com.shopbilling.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Service managing trade discounts and margin protection for technicians and contractors
 * (PRD Section 5, Section 7.3 FR-10, Section 15 Open Question 3).
 * Pure service: strictly contains NO JavaFX imports.
 */
public class TechnicianPricingService {

    private static final Logger logger = LoggerFactory.getLogger(TechnicianPricingService.class);

    public static final String SETTING_TECHNICIAN_DISCOUNT_PERCENT = "TECHNICIAN_DEFAULT_DISCOUNT_PERCENT";
    public static final String SETTING_MIN_PROFIT_MARGIN_PERCENT = "MINIMUM_PROFIT_MARGIN_PERCENT";

    public static final double DEFAULT_TECHNICIAN_DISCOUNT = 10.0; // 10% trade discount
    public static final double DEFAULT_MIN_PROFIT_MARGIN = 5.0;    // 5% minimum profit floor

    private final TransactionHelper transactionHelper;
    private final SettingsRepository settingsRepository;
    private final ProductVariantRepository variantRepository;
    private final ProfitRepository profitRepository;
    private final CustomerRepository customerRepository;
    private final UserRepository userRepository;
    private final AuditLogService auditLogService;

    public TechnicianPricingService(TransactionHelper transactionHelper,
                                    SettingsRepository settingsRepository,
                                    ProductVariantRepository variantRepository,
                                    ProfitRepository profitRepository,
                                    CustomerRepository customerRepository,
                                    UserRepository userRepository,
                                    AuditLogService auditLogService) {
        this.transactionHelper = Objects.requireNonNull(transactionHelper, "TransactionHelper cannot be null");
        this.settingsRepository = Objects.requireNonNull(settingsRepository, "SettingsRepository cannot be null");
        this.variantRepository = Objects.requireNonNull(variantRepository, "ProductVariantRepository cannot be null");
        this.profitRepository = Objects.requireNonNull(profitRepository, "ProfitRepository cannot be null");
        this.customerRepository = Objects.requireNonNull(customerRepository, "CustomerRepository cannot be null");
        this.userRepository = Objects.requireNonNull(userRepository, "UserRepository cannot be null");
        this.auditLogService = Objects.requireNonNull(auditLogService, "AuditLogService cannot be null");
    }

    /**
     * Calculates the effective item unit price based on customer type, applying technician discounts
     * and ensuring the price never falls below the purchase cost plus minimum profit margin (FR-10).
     */
    public TechnicianPriceQuote calculateItemQuote(long variantId, CustomerType customerType) {
        return transactionHelper.executeRead(conn -> {
            ProductVariant variant = variantRepository.findById(conn, variantId)
                    .orElseThrow(() -> new EntityNotFoundException("ProductVariant id=" + variantId + " not found"));

            Money mrp = variant.mrp();
            Money retailSellingPrice = variant.sellingPrice();

            if (customerType == null || customerType == CustomerType.RETAIL) {
                return new TechnicianPriceQuote(
                        variant.id(),
                        variant.variantName(),
                        mrp,
                        retailSellingPrice,
                        0.0,
                        retailSellingPrice,
                        Money.ZERO,
                        false
                );
            }

            // Customer is TECHNICIAN
            double techDiscountPercent = getTechnicianDiscountPercent(conn);
            double minMarginPercent = getMinimumProfitMarginPercent(conn);

            long rawDiscountPaise = Math.round(retailSellingPrice.getPaise() * (techDiscountPercent / 100.0));
            Money proposedPrice = retailSellingPrice.minus(Money.ofPaise(rawDiscountPaise));

            // Check purchase cost floor for margin protection
            Map<Long, Money> unitCosts = profitRepository.getWeightedAverageUnitCosts(conn);
            Money costPrice = unitCosts.get(variantId);

            boolean marginProtected = false;
            Money effectivePrice = proposedPrice;
            double effectivePercent = techDiscountPercent;

            if (costPrice != null && costPrice.isPositive()) {
                long minAllowedFloorPaise = Math.round(costPrice.getPaise() * (1.0 + (minMarginPercent / 100.0)));
                Money minAllowedFloor = Money.ofPaise(minAllowedFloorPaise);

                if (proposedPrice.isLessThan(minAllowedFloor)) {
                    // Margin protection triggers: clamp to margin floor
                    effectivePrice = minAllowedFloor.isLessThan(retailSellingPrice) ? minAllowedFloor : retailSellingPrice;
                    marginProtected = true;
                    Money savings = retailSellingPrice.minus(effectivePrice);
                    effectivePercent = retailSellingPrice.isPositive()
                            ? ((double) savings.getPaise() / (double) retailSellingPrice.getPaise()) * 100.0
                            : 0.0;
                }
            }

            Money savingsPerUnit = retailSellingPrice.minus(effectivePrice);

            return new TechnicianPriceQuote(
                    variant.id(),
                    variant.variantName(),
                    mrp,
                    retailSellingPrice,
                    Math.round(effectivePercent * 100.0) / 100.0,
                    effectivePrice,
                    savingsPerUnit,
                    marginProtected
            );
        });
    }

    /**
     * Calculates quotes across multiple items for a given customer (resolving customer type automatically).
     */
    public TechnicianQuoteSummary calculateBillQuote(List<SaleItemInput> items, Long customerId) {
        if (items == null || items.isEmpty()) {
            throw new ValidationException("Cannot calculate quote for empty items list");
        }

        return transactionHelper.executeRead(conn -> {
            CustomerType customerType = CustomerType.RETAIL;
            if (customerId != null) {
                customerType = customerRepository.findById(conn, customerId)
                        .map(Customer::customerType)
                        .orElse(CustomerType.RETAIL);
            }

            List<TechnicianPriceQuote> quotes = new ArrayList<>();
            Money totalRetail = Money.ZERO;
            Money totalEffective = Money.ZERO;

            for (SaleItemInput item : items) {
                TechnicianPriceQuote quote = calculateItemQuote(item.variantId(), customerType);
                quotes.add(quote);

                totalRetail = totalRetail.plus(quote.retailSellingPrice().times(item.quantity().toBigDecimal(), RoundingMode.HALF_UP));
                totalEffective = totalEffective.plus(quote.unitPrice().times(item.quantity().toBigDecimal(), RoundingMode.HALF_UP));
            }

            Money totalSavings = totalRetail.minus(totalEffective);

            return new TechnicianQuoteSummary(
                    customerType,
                    quotes,
                    totalRetail,
                    totalEffective,
                    totalSavings
            );
        });
    }

    public double getTechnicianDiscountPercent() {
        return transactionHelper.executeRead(this::getTechnicianDiscountPercent);
    }

    public double getMinimumProfitMarginPercent() {
        return transactionHelper.executeRead(this::getMinimumProfitMarginPercent);
    }

    private double getTechnicianDiscountPercent(Connection conn) throws SQLException {
        String val = settingsRepository.getSettingOrDefault(conn, SETTING_TECHNICIAN_DISCOUNT_PERCENT, String.valueOf(DEFAULT_TECHNICIAN_DISCOUNT));
        try {
            return Double.parseDouble(val);
        } catch (NumberFormatException e) {
            return DEFAULT_TECHNICIAN_DISCOUNT;
        }
    }

    private double getMinimumProfitMarginPercent(Connection conn) throws SQLException {
        String val = settingsRepository.getSettingOrDefault(conn, SETTING_MIN_PROFIT_MARGIN_PERCENT, String.valueOf(DEFAULT_MIN_PROFIT_MARGIN));
        try {
            return Double.parseDouble(val);
        } catch (NumberFormatException e) {
            return DEFAULT_MIN_PROFIT_MARGIN;
        }
    }

    public void updateTechnicianDiscountSetting(long requestingUserId, double newPercent) {
        if (newPercent < 0.0 || newPercent > 100.0) {
            throw new ValidationException("Technician discount percentage must be between 0% and 100%: " + newPercent);
        }

        transactionHelper.executeVoid(conn -> {
            verifyOwner(conn, requestingUserId);
            String oldVal = settingsRepository.getSettingOrDefault(conn, SETTING_TECHNICIAN_DISCOUNT_PERCENT, String.valueOf(DEFAULT_TECHNICIAN_DISCOUNT));
            String newVal = String.format("%.2f", newPercent);

            settingsRepository.setSetting(conn, SETTING_TECHNICIAN_DISCOUNT_PERCENT, newVal, requestingUserId);
            auditLogService.logEventInTransaction(
                    conn,
                    requestingUserId,
                    "SETTINGS_CHANGE",
                    "SETTINGS",
                    SETTING_TECHNICIAN_DISCOUNT_PERCENT,
                    oldVal,
                    newVal,
                    "Updated technician discount percentage"
            );
            logger.info("Updated technician discount percent to {} by userId={}", newVal, requestingUserId);
        });
    }

    public void updateMinimumProfitMarginSetting(long requestingUserId, double newPercent) {
        if (newPercent < 0.0 || newPercent > 100.0) {
            throw new ValidationException("Minimum profit margin percentage must be between 0% and 100%: " + newPercent);
        }

        transactionHelper.executeVoid(conn -> {
            verifyOwner(conn, requestingUserId);
            String oldVal = settingsRepository.getSettingOrDefault(conn, SETTING_MIN_PROFIT_MARGIN_PERCENT, String.valueOf(DEFAULT_MIN_PROFIT_MARGIN));
            String newVal = String.format("%.2f", newPercent);

            settingsRepository.setSetting(conn, SETTING_MIN_PROFIT_MARGIN_PERCENT, newVal, requestingUserId);
            auditLogService.logEventInTransaction(
                    conn,
                    requestingUserId,
                    "SETTINGS_CHANGE",
                    "SETTINGS",
                    SETTING_MIN_PROFIT_MARGIN_PERCENT,
                    oldVal,
                    newVal,
                    "Updated minimum profit margin floor"
            );
            logger.info("Updated minimum profit margin percent to {} by userId={}", newVal, requestingUserId);
        });
    }

    private void verifyOwner(Connection conn, long userId) throws SQLException {
        User user = userRepository.findById(conn, userId)
                .orElseThrow(() -> new EntityNotFoundException("User id=" + userId + " not found"));
        if (!user.isOwner()) {
            throw new UnauthorizedException("Only the shop owner is authorized to change pricing settings");
        }
    }
}
