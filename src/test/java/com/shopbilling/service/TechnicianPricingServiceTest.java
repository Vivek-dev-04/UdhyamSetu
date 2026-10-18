package com.shopbilling.service;

import com.shopbilling.db.*;
import com.shopbilling.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TechnicianPricingService Tests (Trade Discounts & Margin Protection)")
class TechnicianPricingServiceTest {

    @TempDir
    Path tempDir;

    private ConnectionFactory connectionFactory;
    private TransactionHelper transactionHelper;
    private SettingsRepository settingsRepository;
    private ProductVariantRepository variantRepository;
    private ProfitRepository profitRepository;
    private CustomerRepository customerRepository;
    private UserRepository userRepository;
    private AuditLogService auditLogService;

    private TechnicianPricingService pricingService;

    private long ownerUserId;
    private long staffUserId;
    private long retailCustomerId;
    private long technicianCustomerId;
    private long normalMarginVariantId;
    private long thinMarginVariantId;

    @BeforeEach
    void setUp() throws IOException {
        Path dbFile = tempDir.resolve("technician_pricing_test.db");
        DatabaseConfig config = DatabaseConfig.forFile(dbFile);
        connectionFactory = new ConnectionFactory(config);
        new FlywayMigrator(connectionFactory).migrate();

        transactionHelper = new TransactionHelper(connectionFactory);
        settingsRepository = new SettingsRepository();
        variantRepository = new ProductVariantRepository();
        profitRepository = new ProfitRepository();
        customerRepository = new CustomerRepository();
        userRepository = new UserRepository();
        AuditLogRepository auditLogRepository = new AuditLogRepository();
        auditLogService = new AuditLogService(transactionHelper, auditLogRepository, userRepository);

        pricingService = new TechnicianPricingService(
                transactionHelper,
                settingsRepository,
                variantRepository,
                profitRepository,
                customerRepository,
                userRepository,
                auditLogService
        );

        BrandRepository brandRepository = new BrandRepository();
        CategoryRepository categoryRepository = new CategoryRepository();
        ProductRepository productRepository = new ProductRepository();
        PurchaseRepository purchaseRepository = new PurchaseRepository();
        SupplierRepository supplierRepository = new SupplierRepository();

        transactionHelper.executeVoid(conn -> {
            User owner = userRepository.insert(conn, new User(null, "owner", "pass", UserRole.OWNER, "Shop Owner", true, null));
            User staff = userRepository.insert(conn, new User(null, "staff", "pass", UserRole.STAFF, "Counter Staff", true, null));
            ownerUserId = owner.id();
            staffUserId = staff.id();

            Customer retail = customerRepository.insert(conn, new Customer(
                    null, "Ramesh Retail", "9999911111", CustomerType.RETAIL, "Main Bazaar", null, null, null
            ));
            Customer tech = customerRepository.insert(conn, new Customer(
                    null, "Mukesh Electrician", "9999922222", CustomerType.TECHNICIAN, "Workshop 4", null, null, null
            ));
            retailCustomerId = retail.id();
            technicianCustomerId = tech.id();

            Supplier supplier = supplierRepository.insert(conn, new Supplier(
                    null, "Polycab Wires", "9876500000", "Ajay", "Depot", null, null, null
            ));

            Brand brand = brandRepository.insert(conn, "Polycab");
            Category cat = categoryRepository.insert(conn, "Wires");
            Product prod = productRepository.insert(conn, new Product(null, brand.id(), cat.id(), "House Wire", "COIL", null, true, null));

            // Item 1: MRP 1000, Retail selling price 800, Purchase Cost 500 (Heathy margin)
            // 10% tech discount -> proposed 720, cost floor @ 5% = 525 -> Tech price = 720 (Margin NOT violated)
            ProductVariant v1 = variantRepository.insert(conn, new ProductVariant(null, prod.id(), "1.5 sq mm Red", Money.ofRupees(1000), Money.ofRupees(800), "BAR-P1", true, null));

            // Item 2: MRP 1000, Retail selling price 800, Purchase Cost 750 (Very thin margin)
            // 10% tech discount -> proposed 720, but cost floor @ 5% = 750 * 1.05 = 787.50 (~788).
            // Proposed 720 < 788 -> Margin guard triggers! Tech price clamped to 788.
            ProductVariant v2 = variantRepository.insert(conn, new ProductVariant(null, prod.id(), "2.5 sq mm Blue", Money.ofRupees(1000), Money.ofRupees(800), "BAR-P2", true, null));

            normalMarginVariantId = v1.id();
            thinMarginVariantId = v2.id();

            // Record purchase history to establish weighted average unit costs
            Purchase p1 = purchaseRepository.insertPurchase(conn, new Purchase(
                    null, supplier.id(), "INV-01", LocalDate.now(), Money.ofRupees(5000), Money.ofRupees(5000), Money.ZERO, null, null, ownerUserId, List.of()
            ));
            purchaseRepository.insertPurchaseItem(conn, new PurchaseItem(null, p1.id(), normalMarginVariantId, Quantity.of(10), Money.ofRupees(500), Money.ofRupees(5000)));

            Purchase p2 = purchaseRepository.insertPurchase(conn, new Purchase(
                    null, supplier.id(), "INV-02", LocalDate.now(), Money.ofRupees(7500), Money.ofRupees(7500), Money.ZERO, null, null, ownerUserId, List.of()
            ));
            purchaseRepository.insertPurchaseItem(conn, new PurchaseItem(null, p2.id(), thinMarginVariantId, Quantity.of(10), Money.ofRupees(750), Money.ofRupees(7500)));
        });
    }

    @Test
    @DisplayName("Retail customer quote receives standard retail selling price without discount")
    void testRetailCustomerQuote() {
        TechnicianPriceQuote quote = pricingService.calculateItemQuote(normalMarginVariantId, CustomerType.RETAIL);

        assertNotNull(quote);
        assertEquals(Money.ofRupees(800), quote.retailSellingPrice());
        assertEquals(Money.ofRupees(800), quote.unitPrice());
        assertEquals(0.0, quote.discountPercent());
        assertEquals(Money.ZERO, quote.savingsPerUnit());
        assertFalse(quote.marginProtected());
    }

    @Test
    @DisplayName("Technician receives 10% trade discount when margin is healthy")
    void testTechnicianHealthyMarginQuote() {
        TechnicianPriceQuote quote = pricingService.calculateItemQuote(normalMarginVariantId, CustomerType.TECHNICIAN);

        assertNotNull(quote);
        assertEquals(Money.ofRupees(800), quote.retailSellingPrice());
        // 800 - 10% = 720
        assertEquals(Money.ofRupees(720), quote.unitPrice());
        assertEquals(10.0, quote.discountPercent());
        assertEquals(Money.ofRupees(80), quote.savingsPerUnit());
        assertFalse(quote.marginProtected());
    }

    @Test
    @DisplayName("Margin guard clamps discount when proposed price falls below purchase cost + min margin")
    void testMarginGuardProtection() {
        TechnicianPriceQuote quote = pricingService.calculateItemQuote(thinMarginVariantId, CustomerType.TECHNICIAN);

        assertNotNull(quote);
        assertEquals(Money.ofRupees(800), quote.retailSellingPrice());
        // Cost is 750. Min margin is 5% -> 750 * 1.05 = 787.50 -> rounded to 787.50 / 788.00 paise
        assertTrue(quote.marginProtected(), "Margin protection MUST trigger");
        assertTrue(quote.unitPrice().isGreaterThan(Money.ofRupees(780)), "Price must be clamped above cost floor");
        assertTrue(quote.unitPrice().isLessThan(Money.ofRupees(800)), "Price should still offer some savings");
        assertTrue(quote.discountPercent() < 10.0, "Discount percentage is clamped");
    }

    @Test
    @DisplayName("Multi-item bill quote accurately resolves customer type and computes totals")
    void testCalculateBillQuote() {
        List<SaleItemInput> items = List.of(
                new SaleItemInput(normalMarginVariantId, Quantity.of(2), Money.ofRupees(800), Money.ZERO)
        );

        // Retail customer
        TechnicianQuoteSummary retailSummary = pricingService.calculateBillQuote(items, retailCustomerId);
        assertEquals(CustomerType.RETAIL, retailSummary.customerType());
        assertEquals(Money.ofRupees(1600), retailSummary.totalRetailSubtotal());
        assertEquals(Money.ofRupees(1600), retailSummary.totalEffectiveSubtotal());
        assertEquals(Money.ZERO, retailSummary.totalSavings());

        // Technician customer
        TechnicianQuoteSummary techSummary = pricingService.calculateBillQuote(items, technicianCustomerId);
        assertEquals(CustomerType.TECHNICIAN, techSummary.customerType());
        assertEquals(Money.ofRupees(1600), techSummary.totalRetailSubtotal());
        // 2 units @ 720 = 1440
        assertEquals(Money.ofRupees(1440), techSummary.totalEffectiveSubtotal());
        assertEquals(Money.ofRupees(160), techSummary.totalSavings());
    }

    @Test
    @DisplayName("Owner can update technician discount and margin floor settings with audit logging")
    void testUpdateSettings() {
        pricingService.updateTechnicianDiscountSetting(ownerUserId, 12.5);
        assertEquals(12.5, pricingService.getTechnicianDiscountPercent(), 0.001);

        pricingService.updateMinimumProfitMarginSetting(ownerUserId, 7.5);
        assertEquals(7.5, pricingService.getMinimumProfitMarginPercent(), 0.001);

        // Staff user cannot update settings
        assertThrows(UnauthorizedException.class, () ->
                pricingService.updateTechnicianDiscountSetting(staffUserId, 15.0)
        );

        // Invalid percentage throws
        assertThrows(ValidationException.class, () ->
                pricingService.updateTechnicianDiscountSetting(ownerUserId, 120.0)
        );
    }
}
