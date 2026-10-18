package com.shopbilling.service;

import com.shopbilling.db.*;
import com.shopbilling.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ShopSettingsService Tests (Configuration, Validation, Authorization & Audit Logging)")
class ShopSettingsServiceTest {

    @TempDir
    Path tempDir;

    private ConnectionFactory connectionFactory;
    private TransactionHelper transactionHelper;
    private SettingsRepository settingsRepository;
    private UserRepository userRepository;
    private AuditLogRepository auditLogRepository;
    private AuditLogService auditLogService;
    private ShopSettingsService shopSettingsService;

    private long ownerUserId;
    private long staffUserId;

    @BeforeEach
    void setUp() throws IOException {
        Path dbFile = tempDir.resolve("shop_settings_test.db");
        DatabaseConfig config = DatabaseConfig.forFile(dbFile);
        connectionFactory = new ConnectionFactory(config);
        new FlywayMigrator(connectionFactory).migrate();

        transactionHelper = new TransactionHelper(connectionFactory);
        settingsRepository = new SettingsRepository();
        userRepository = new UserRepository();
        auditLogRepository = new AuditLogRepository();
        auditLogService = new AuditLogService(transactionHelper, auditLogRepository, userRepository);

        shopSettingsService = new ShopSettingsService(
                transactionHelper,
                settingsRepository,
                userRepository,
                auditLogService
        );

        ownerUserId = transactionHelper.execute(conn -> {
            User owner = userRepository.insert(conn, new User(
                    null, "owner", "hashed", UserRole.OWNER, "Shop Owner", true, null
            ));
            return owner.id();
        });

        staffUserId = transactionHelper.execute(conn -> {
            User staff = userRepository.insert(conn, new User(
                    null, "staff", "hashed", UserRole.STAFF, "Counter Staff", true, null
            ));
            return staff.id();
        });
    }

    @Test
    @DisplayName("getShopProfile returns valid defaults from initial migration")
    void testGetShopProfileDefaults() {
        ShopProfile profile = shopSettingsService.getShopProfile();

        assertNotNull(profile);
        assertNotNull(profile.shopName());
        assertFalse(profile.allowNegativeStock());
        assertEquals(new BigDecimal("5.00"), profile.staffMaxDiscountPercent());
        assertEquals(50000L, profile.staffReturnApprovalLimitPaise());
        assertTrue(profile.staffCanRegisterSale());
        assertEquals(14, profile.backupRetentionCount());
        assertEquals(ThermalWidth.WIDTH_58MM, profile.thermalReceiptWidth());
    }

    @Test
    @DisplayName("updateShopProfile updates configuration and writes audit logs for changed fields")
    void testUpdateShopProfileByOwner() {
        ShopProfile updated = new ShopProfile(
                "Shree Ganesh Hardware",
                "Electricals & Tools",
                "Shop 12, Main Market, Mumbai",
                "9876500000",
                "27ABCDE1234F1Z5",
                ThermalWidth.WIDTH_80MM,
                "No returns after 15 days.",
                new BigDecimal("10.00"),
                true,
                75000L,
                true,
                30,
                "/backup/external"
        );

        ShopProfile result = shopSettingsService.updateShopProfile(ownerUserId, updated);

        assertEquals("Shree Ganesh Hardware", result.shopName());
        assertEquals("Electricals & Tools", result.shopTagline());
        assertEquals("Shop 12, Main Market, Mumbai", result.shopAddress());
        assertEquals("9876500000", result.shopPhone());
        assertEquals("27ABCDE1234F1Z5", result.shopGstin());
        assertEquals(ThermalWidth.WIDTH_80MM, result.thermalReceiptWidth());
        assertEquals("No returns after 15 days.", result.termsAndConditions());
        assertEquals(new BigDecimal("10.00"), result.staffMaxDiscountPercent());
        assertEquals(75000L, result.staffReturnApprovalLimitPaise());
        assertTrue(result.allowNegativeStock());
        assertEquals(30, result.backupRetentionCount());
        assertEquals("/backup/external", result.backupExternalDirectory());

        // Verify retrieval matches
        ShopProfile fetched = shopSettingsService.getShopProfile();
        assertEquals("Shree Ganesh Hardware", fetched.shopName());
        assertEquals(ThermalWidth.WIDTH_80MM, fetched.thermalReceiptWidth());
        assertTrue(fetched.allowNegativeStock());

        // Verify audit logs were written (FR-44)
        List<AuditLogRecord> logs = auditLogService.getRecentLogs(ownerUserId, 50);
        assertFalse(logs.isEmpty());
        assertTrue(logs.stream().anyMatch(l -> "SETTING_CHANGED".equals(l.action()) && "SHOP_NAME".equals(l.entityId())));
        assertTrue(logs.stream().anyMatch(l -> "SETTING_CHANGED".equals(l.action()) && "ALLOW_NEGATIVE_STOCK".equals(l.entityId())));
    }

    @Test
    @DisplayName("Staff user cannot update shop profile and receives UnauthorizedException")
    void testStaffCannotUpdateProfile() {
        ShopProfile profile = shopSettingsService.getShopProfile();

        assertThrows(UnauthorizedException.class, () ->
                shopSettingsService.updateShopProfile(staffUserId, profile)
        );

        assertThrows(UnauthorizedException.class, () ->
                shopSettingsService.setSetting(staffUserId, "SHOP_NAME", "Hacked Shop", "Unauthorized attempt")
        );
    }

    @Test
    @DisplayName("Profile validation throws ValidationException on invalid values")
    void testProfileValidation() {
        // Blank shop name
        assertThrows(ValidationException.class, () ->
                shopSettingsService.updateShopProfile(ownerUserId, new ShopProfile(
                        "   ", "", "", "", "", ThermalWidth.WIDTH_58MM, "",
                        BigDecimal.TEN, true, 1000L, false, 14, ""
                ))
        );

        // Negative discount percent
        assertThrows(ValidationException.class, () ->
                shopSettingsService.updateShopProfile(ownerUserId, new ShopProfile(
                        "Valid Name", "", "", "", "", ThermalWidth.WIDTH_58MM, "",
                        new BigDecimal("-1.00"), true, 1000L, false, 14, ""
                ))
        );

        // Discount percent > 100
        assertThrows(ValidationException.class, () ->
                shopSettingsService.updateShopProfile(ownerUserId, new ShopProfile(
                        "Valid Name", "", "", "", "", ThermalWidth.WIDTH_58MM, "",
                        new BigDecimal("105.00"), true, 1000L, false, 14, ""
                ))
        );

        // Retention count < 1
        assertThrows(ValidationException.class, () ->
                shopSettingsService.updateShopProfile(ownerUserId, new ShopProfile(
                        "Valid Name", "", "", "", "", ThermalWidth.WIDTH_58MM, "",
                        BigDecimal.TEN, true, 1000L, false, 0, ""
                ))
        );

        // GSTIN too long
        assertThrows(ValidationException.class, () ->
                shopSettingsService.updateShopProfile(ownerUserId, new ShopProfile(
                        "Valid Name", "", "", "", "12345678901234567890123", ThermalWidth.WIDTH_58MM, "",
                        BigDecimal.TEN, true, 1000L, false, 14, ""
                ))
        );
    }

    @Test
    @DisplayName("Individual setSetting updates value and writes audit log")
    void testSetIndividualSetting() {
        shopSettingsService.setSetting(ownerUserId, "SHOP_NAME", "Super Mart", "Rebranding shop");

        String fetched = shopSettingsService.getSettingOrDefault("SHOP_NAME", "fallback");
        assertEquals("Super Mart", fetched);

        List<AuditLogRecord> logs = auditLogService.getRecentLogs(ownerUserId, 10);
        AuditLogRecord log = logs.stream()
                .filter(l -> "SHOP_NAME".equals(l.entityId()))
                .findFirst()
                .orElse(null);

        assertNotNull(log);
        assertEquals("SETTING_CHANGED", log.action());
        assertEquals("Super Mart", log.newValue());
        assertEquals("Rebranding shop", log.reason());
    }
}
