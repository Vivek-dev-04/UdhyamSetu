package com.shopbilling.service;

import com.shopbilling.db.SettingsRepository;
import com.shopbilling.db.TransactionHelper;
import com.shopbilling.db.UserRepository;
import com.shopbilling.model.ShopProfile;
import com.shopbilling.model.ThermalWidth;
import com.shopbilling.model.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

/**
 * Service managing typed shop settings, security validation, owner-only authorization,
 * and append-only audit logging for configuration modifications (PRD Section 7.10, FR-44).
 * Strictly contains NO JavaFX imports.
 */
public class ShopSettingsService {

    private static final Logger logger = LoggerFactory.getLogger(ShopSettingsService.class);

    // Setting Key Constants
    public static final String KEY_SHOP_NAME = "SHOP_NAME";
    public static final String KEY_SHOP_TAGLINE = "SHOP_TAGLINE";
    public static final String KEY_SHOP_ADDRESS = "SHOP_ADDRESS";
    public static final String KEY_SHOP_PHONE = "SHOP_PHONE";
    public static final String KEY_SHOP_GSTIN = "SHOP_GSTIN";
    public static final String KEY_THERMAL_RECEIPT_WIDTH = "THERMAL_RECEIPT_WIDTH";
    public static final String KEY_TERMS_AND_CONDITIONS = "TERMS_AND_CONDITIONS";
    public static final String KEY_STAFF_MAX_DISCOUNT_PERCENT = "STAFF_MAX_DISCOUNT_PERCENT";
    public static final String KEY_STAFF_CAN_REGISTER_SALE = "STAFF_CAN_REGISTER_SALE";
    public static final String KEY_STAFF_RETURN_APPROVAL_LIMIT_PAISE = "STAFF_RETURN_APPROVAL_LIMIT_PAISE";
    public static final String KEY_ALLOW_NEGATIVE_STOCK = "ALLOW_NEGATIVE_STOCK";
    public static final String KEY_BACKUP_RETENTION_COUNT = "BACKUP_RETENTION_COUNT";
    public static final String KEY_BACKUP_EXTERNAL_DIRECTORY = "BACKUP_EXTERNAL_DIRECTORY";

    private final TransactionHelper transactionHelper;
    private final SettingsRepository settingsRepository;
    private final UserRepository userRepository;
    private final AuditLogService auditLogService;

    public ShopSettingsService(TransactionHelper transactionHelper,
                               SettingsRepository settingsRepository,
                               UserRepository userRepository,
                               AuditLogService auditLogService) {
        this.transactionHelper = Objects.requireNonNull(transactionHelper, "TransactionHelper cannot be null");
        this.settingsRepository = Objects.requireNonNull(settingsRepository, "SettingsRepository cannot be null");
        this.userRepository = Objects.requireNonNull(userRepository, "UserRepository cannot be null");
        this.auditLogService = Objects.requireNonNull(auditLogService, "AuditLogService cannot be null");
    }

    /**
     * Reads all settings and constructs a typed ShopProfile.
     */
    public ShopProfile getShopProfile() {
        return transactionHelper.executeRead(conn -> {
            String shopName = settingsRepository.getSettingOrDefault(conn, KEY_SHOP_NAME, "UdhyamSetu Retail Store");
            String shopTagline = settingsRepository.getSettingOrDefault(conn, KEY_SHOP_TAGLINE, "Quality Goods & Retail");
            String shopAddress = settingsRepository.getSettingOrDefault(conn, KEY_SHOP_ADDRESS, "Main Market");
            String shopPhone = settingsRepository.getSettingOrDefault(conn, KEY_SHOP_PHONE, "9876543210");
            String shopGstin = settingsRepository.getSettingOrDefault(conn, KEY_SHOP_GSTIN, "");

            String widthStr = settingsRepository.getSettingOrDefault(conn, KEY_THERMAL_RECEIPT_WIDTH, "WIDTH_58MM");
            ThermalWidth width = ThermalWidth.WIDTH_58MM;
            try {
                width = ThermalWidth.valueOf(widthStr);
            } catch (Exception ignored) {}

            String terms = settingsRepository.getSettingOrDefault(conn, KEY_TERMS_AND_CONDITIONS, "Goods once sold cannot be returned without original bill.");

            String discStr = settingsRepository.getSettingOrDefault(conn, KEY_STAFF_MAX_DISCOUNT_PERCENT, "5.00");
            BigDecimal staffDiscount;
            try {
                staffDiscount = new BigDecimal(discStr);
            } catch (Exception e) {
                staffDiscount = new BigDecimal("5.00");
            }

            boolean canRegisterSale = Boolean.parseBoolean(settingsRepository.getSettingOrDefault(conn, KEY_STAFF_CAN_REGISTER_SALE, "true"));

            String returnLimitStr = settingsRepository.getSettingOrDefault(conn, KEY_STAFF_RETURN_APPROVAL_LIMIT_PAISE, "50000");
            long returnLimit;
            try {
                returnLimit = Long.parseLong(returnLimitStr);
            } catch (Exception e) {
                returnLimit = 50000L;
            }

            boolean allowNegStock = Boolean.parseBoolean(settingsRepository.getSettingOrDefault(conn, KEY_ALLOW_NEGATIVE_STOCK, "false"));

            String retCountStr = settingsRepository.getSettingOrDefault(conn, KEY_BACKUP_RETENTION_COUNT, "14");
            int retentionCount;
            try {
                retentionCount = Integer.parseInt(retCountStr);
            } catch (Exception e) {
                retentionCount = 14;
            }

            String backupDir = settingsRepository.getSettingOrDefault(conn, KEY_BACKUP_EXTERNAL_DIRECTORY, "");

            return new ShopProfile(
                    shopName,
                    shopTagline,
                    shopAddress,
                    shopPhone,
                    shopGstin,
                    width,
                    terms,
                    staffDiscount,
                    canRegisterSale,
                    returnLimit,
                    allowNegStock,
                    retentionCount,
                    backupDir
            );
        });
    }

    /**
     * Updates the full shop profile. Only OWNER can perform this operation (FR-44).
     * Validates input values and records audit log entries for each modified setting.
     */
    public ShopProfile updateShopProfile(long requestingUserId, ShopProfile updatedProfile) {
        Objects.requireNonNull(updatedProfile, "ShopProfile cannot be null");
        validateProfile(updatedProfile);

        return transactionHelper.execute(conn -> {
            verifyOwner(conn, requestingUserId);

            updateSettingIfChanged(conn, requestingUserId, KEY_SHOP_NAME, updatedProfile.shopName(), "Shop name updated");
            updateSettingIfChanged(conn, requestingUserId, KEY_SHOP_TAGLINE, updatedProfile.shopTagline(), "Shop tagline updated");
            updateSettingIfChanged(conn, requestingUserId, KEY_SHOP_ADDRESS, updatedProfile.shopAddress(), "Shop address updated");
            updateSettingIfChanged(conn, requestingUserId, KEY_SHOP_PHONE, updatedProfile.shopPhone(), "Shop phone updated");
            updateSettingIfChanged(conn, requestingUserId, KEY_SHOP_GSTIN, updatedProfile.shopGstin(), "Shop GSTIN updated");
            updateSettingIfChanged(conn, requestingUserId, KEY_THERMAL_RECEIPT_WIDTH, updatedProfile.thermalReceiptWidth().name(), "Thermal receipt width updated");
            updateSettingIfChanged(conn, requestingUserId, KEY_TERMS_AND_CONDITIONS, updatedProfile.termsAndConditions(), "Terms & conditions updated");
            updateSettingIfChanged(conn, requestingUserId, KEY_STAFF_MAX_DISCOUNT_PERCENT, updatedProfile.staffMaxDiscountPercent().toPlainString(), "Staff max discount % updated");
            updateSettingIfChanged(conn, requestingUserId, KEY_STAFF_CAN_REGISTER_SALE, String.valueOf(updatedProfile.staffCanRegisterSale()), "Staff can register sale flag updated");
            updateSettingIfChanged(conn, requestingUserId, KEY_STAFF_RETURN_APPROVAL_LIMIT_PAISE, String.valueOf(updatedProfile.staffReturnApprovalLimitPaise()), "Staff return approval limit updated");
            updateSettingIfChanged(conn, requestingUserId, KEY_ALLOW_NEGATIVE_STOCK, String.valueOf(updatedProfile.allowNegativeStock()), "Allow negative stock flag updated");
            updateSettingIfChanged(conn, requestingUserId, KEY_BACKUP_RETENTION_COUNT, String.valueOf(updatedProfile.backupRetentionCount()), "Backup retention count updated");
            updateSettingIfChanged(conn, requestingUserId, KEY_BACKUP_EXTERNAL_DIRECTORY, updatedProfile.backupExternalDirectory(), "Backup external directory updated");

            logger.info("ShopProfile successfully updated by owner userId={}", requestingUserId);
            return updatedProfile;
        });
    }

    /**
     * Updates an individual setting with owner authorization check and audit trail logging.
     */
    public void setSetting(long requestingUserId, String key, String value, String reason) {
        Objects.requireNonNull(key, "Key cannot be null");
        Objects.requireNonNull(value, "Value cannot be null");

        transactionHelper.executeVoid(conn -> {
            verifyOwner(conn, requestingUserId);
            updateSettingIfChanged(conn, requestingUserId, key.trim().toUpperCase(), value.trim(), reason != null ? reason : "Setting " + key + " changed");
        });
    }

    public String getSettingOrDefault(String key, String defaultValue) {
        Objects.requireNonNull(key, "Key cannot be null");
        return transactionHelper.executeRead(conn ->
                settingsRepository.getSettingOrDefault(conn, key.trim().toUpperCase(), defaultValue)
        );
    }

    private void updateSettingIfChanged(Connection conn, long userId, String key, String newValue, String auditReason) throws SQLException {
        String oldValue = settingsRepository.getSetting(conn, key).orElse(null);
        if (Objects.equals(oldValue, newValue)) {
            return; // No change
        }

        settingsRepository.setSetting(conn, key, newValue, userId);
        auditLogService.logEventInTransaction(
                conn,
                userId,
                "SETTING_CHANGED",
                "SETTING",
                key,
                oldValue,
                newValue,
                auditReason
        );
    }

    private void verifyOwner(Connection conn, long userId) throws SQLException {
        User user = userRepository.findById(conn, userId)
                .orElseThrow(() -> new EntityNotFoundException("User id=" + userId + " not found"));
        if (!user.isOwner()) {
            throw new UnauthorizedException("Only the shop owner is authorized to modify settings");
        }
    }

    private void validateProfile(ShopProfile profile) {
        if (profile.shopName() == null || profile.shopName().trim().isEmpty()) {
            throw new ValidationException("Shop name cannot be blank");
        }
        if (profile.staffMaxDiscountPercent() == null ||
                profile.staffMaxDiscountPercent().compareTo(BigDecimal.ZERO) < 0 ||
                profile.staffMaxDiscountPercent().compareTo(new BigDecimal("100.00")) > 0) {
            throw new ValidationException("Staff max discount must be between 0.00% and 100.00%");
        }
        if (profile.staffReturnApprovalLimitPaise() < 0) {
            throw new ValidationException("Staff return approval limit cannot be negative");
        }
        if (profile.backupRetentionCount() < 1) {
            throw new ValidationException("Backup retention count must be at least 1");
        }
        if (profile.shopGstin() != null && !profile.shopGstin().trim().isEmpty() && profile.shopGstin().trim().length() > 20) {
            throw new ValidationException("GSTIN length cannot exceed 20 characters");
        }
    }
}
