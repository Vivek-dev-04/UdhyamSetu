package com.shopbilling.ui;

import com.shopbilling.db.DataDirectoryHandler;
import com.shopbilling.model.User;
import com.shopbilling.model.UserRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("AppContext Service Registry and Initialization Tests")
class AppContextTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("AppContext successfully initializes all repositories, services, and Flyway database")
    void testAppContextInitialization() {
        DataDirectoryHandler handler = new DataDirectoryHandler(tempDir.resolve("data"));
        AppContext context = new AppContext(handler);

        // Core Infrastructure
        assertNotNull(context.getConnectionFactory());
        assertNotNull(context.getTransactionHelper());
        assertNotNull(context.getClock());

        // Business Services
        assertNotNull(context.getAuthService());
        assertNotNull(context.getUserService());
        assertNotNull(context.getAuditLogService());
        assertNotNull(context.getBrandCategoryService());
        assertNotNull(context.getProductService());
        assertNotNull(context.getStockService());
        assertNotNull(context.getStockInService());
        assertNotNull(context.getCustomerSupplierService());
        assertNotNull(context.getGenerateBillService());
        assertNotNull(context.getRegisterSaleService());
        assertNotNull(context.getReturnService());
        assertNotNull(context.getCreditLedgerService());
        assertNotNull(context.getExpenseService());
        assertNotNull(context.getDayEndClosingService());
        assertNotNull(context.getReportService());
        assertNotNull(context.getProfitService());
        assertNotNull(context.getBackupService());
        assertNotNull(context.getDiagnosticsService());
        assertNotNull(context.getBillPdfService());
        assertNotNull(context.getThermalPrinterService());
        assertNotNull(context.getScheduledBackupService());
        assertNotNull(context.getShopSettingsService());
        assertNotNull(context.getConvertRegisteredSaleService());
        assertNotNull(context.getLowStockService());
        assertNotNull(context.getSupplierReturnService());
        assertNotNull(context.getStockAdjustmentService());
        assertNotNull(context.getTechnicianPricingService());
        assertNotNull(context.getPaymentAllocationService());
        assertNotNull(context.getCustomerStatementPdfService());
        assertNotNull(context.getCatalogueSearchService());
        assertNotNull(context.getCreditNotePdfService());
        assertNotNull(context.getDayEndReportService());
        assertNotNull(context.getSupplierStatementPdfService());
        assertNotNull(context.getBarcodeLabelService());
        assertNotNull(context.getGstReportService());
        assertNotNull(context.getDatabaseMaintenanceService());
        assertNotNull(context.getWarrantyTrackingService());
        assertNotNull(context.getCategoryBrandAnalyticsService());
        assertNotNull(context.getAuditAnomalyService());
        assertNotNull(context.getCloudBackupSyncService());
        assertNotNull(context.getI18nService());

        // Initial user state
        assertNull(context.getCurrentUser());
        assertTrue(context.getAuthService().isFirstLaunch(), "Fresh DB should be in first launch mode");

        // First launch owner registration
        User owner = context.getAuthService().setupInitialOwner("owner", "SecurePass123!", "Shop Owner");
        assertNotNull(owner);
        assertEquals("owner", owner.username());
        assertEquals(UserRole.OWNER, owner.role());
        assertFalse(context.getAuthService().isFirstLaunch(), "First launch should now be false");

        // User session management
        User authenticated = context.getAuthService().authenticate("owner", "SecurePass123!");
        context.setCurrentUser(authenticated);
        assertNotNull(context.getCurrentUser());
        assertEquals("owner", context.getCurrentUser().username());

        context.setCurrentUser(null);
        assertNull(context.getCurrentUser());
    }
}
