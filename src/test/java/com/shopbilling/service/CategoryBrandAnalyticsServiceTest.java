package com.shopbilling.service;

import com.shopbilling.db.BrandRepository;
import com.shopbilling.db.CategoryRepository;
import com.shopbilling.db.ConnectionFactory;
import com.shopbilling.db.CustomerRepository;
import com.shopbilling.db.DatabaseConfig;
import com.shopbilling.db.FlywayMigrator;
import com.shopbilling.db.ProductRepository;
import com.shopbilling.db.ProductVariantRepository;
import com.shopbilling.db.ReturnRepository;
import com.shopbilling.db.SaleRepository;
import com.shopbilling.db.TransactionHelper;
import com.shopbilling.db.UserRepository;
import com.shopbilling.model.Brand;
import com.shopbilling.model.BrandPerformanceRow;
import com.shopbilling.model.Category;
import com.shopbilling.model.CategoryBrandAnalyticsResult;
import com.shopbilling.model.CategoryPerformanceRow;
import com.shopbilling.model.Customer;
import com.shopbilling.model.CustomerType;
import com.shopbilling.model.FinancialYear;
import com.shopbilling.model.Money;
import com.shopbilling.model.PaymentMode;
import com.shopbilling.model.Product;
import com.shopbilling.model.ProductVariant;
import com.shopbilling.model.Quantity;
import com.shopbilling.model.Quarter;
import com.shopbilling.model.ReturnItemRecord;
import com.shopbilling.model.ReturnRecord;
import com.shopbilling.model.Sale;
import com.shopbilling.model.SaleItem;
import com.shopbilling.model.SaleMode;
import com.shopbilling.model.User;
import com.shopbilling.model.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CategoryBrandAnalyticsService Tests (Brand & Category Margins & Velocity)")
class CategoryBrandAnalyticsServiceTest {

    @TempDir
    Path tempDir;

    private ConnectionFactory connectionFactory;
    private TransactionHelper transactionHelper;
    private IstClock clock;

    private CategoryBrandAnalyticsService analyticsService;

    private long ownerUserId;
    private long staffUserId;

    private final LocalDate testDate = LocalDate.of(2026, 6, 15);

    @BeforeEach
    void setUp() throws IOException {
        Path dbFile = tempDir.resolve("category_brand_analytics_test.db");
        DatabaseConfig config = DatabaseConfig.forFile(dbFile);
        connectionFactory = new ConnectionFactory(config);
        new FlywayMigrator(connectionFactory).migrate();

        transactionHelper = new TransactionHelper(connectionFactory);
        clock = IstClock.fixedAtStartOfDay(testDate);
        analyticsService = new CategoryBrandAnalyticsService(transactionHelper, clock);

        UserRepository userRepository = new UserRepository();
        CustomerRepository customerRepository = new CustomerRepository();
        BrandRepository brandRepository = new BrandRepository();
        CategoryRepository categoryRepository = new CategoryRepository();
        ProductRepository productRepository = new ProductRepository();
        ProductVariantRepository variantRepository = new ProductVariantRepository();
        SaleRepository saleRepository = new SaleRepository();
        ReturnRepository returnRepository = new ReturnRepository();

        transactionHelper.executeVoid(conn -> {
            User owner = userRepository.insert(conn, new User(null, "owner", "pass", UserRole.OWNER, "Shop Owner", true, null));
            ownerUserId = owner.id();

            User staff = userRepository.insert(conn, new User(null, "staff", "pass", UserRole.STAFF, "Counter Staff", true, null));
            staffUserId = staff.id();

            Customer cust = customerRepository.insert(conn, new Customer(
                    null, "Ramesh", "9829000000", CustomerType.RETAIL, "Kota", null, null, null
            ));

            // Brands
            Brand havells = brandRepository.insert(conn, "Havells");
            Brand anchor = brandRepository.insert(conn, "Anchor");

            // Categories
            Category switchCat = categoryRepository.insert(conn, "Modular Switches");
            Category fanCat = categoryRepository.insert(conn, "Ceiling Fans");

            // Products
            Product p1 = productRepository.insert(conn, new Product(null, havells.id(), switchCat.id(), "Fabio Switch", "PIECE", null, true, null));
            ProductVariant v1 = variantRepository.insert(conn, new ProductVariant(null, p1.id(), "White 6A", Money.ofRupees(150), Money.ofRupees(120), "BAR-SW1", true, null));

            Product p2 = productRepository.insert(conn, new Product(null, anchor.id(), fanCat.id(), "Roma Fan", "PIECE", null, true, null));
            ProductVariant v2 = variantRepository.insert(conn, new ProductVariant(null, p2.id(), "Brown 1200mm", Money.ofRupees(2200), Money.ofRupees(1800), "BAR-FAN1", true, null));

            // Sale 1: 10 switches (Havells, Modular Switches) = ₹1,200
            Sale s1 = new Sale(null, SaleMode.BILL, "2026-27", "FY26-27/0001", 1, cust.id(), testDate, null, Money.ofRupees(1200), Money.ZERO, Money.ofRupees(1200), ownerUserId, List.of(), List.of());
            Sale savedS1 = saleRepository.insertSale(conn, s1);
            SaleItem savedSi1 = saleRepository.insertSaleItem(conn, new SaleItem(null, savedS1.id(), v1.id(), Quantity.of(10), Money.ofRupees(120), Money.ZERO, Money.ofRupees(1200)));

            // Sale 2: 2 fans (Anchor, Ceiling Fans) = ₹3,600
            Sale s2 = new Sale(null, SaleMode.BILL, "2026-27", "FY26-27/0002", 2, cust.id(), testDate, null, Money.ofRupees(3600), Money.ZERO, Money.ofRupees(3600), ownerUserId, List.of(), List.of());
            Sale savedS2 = saleRepository.insertSale(conn, s2);
            saleRepository.insertSaleItem(conn, new SaleItem(null, savedS2.id(), v2.id(), Quantity.of(2), Money.ofRupees(1800), Money.ZERO, Money.ofRupees(3600)));

            // Return on Sale 1: 2 switches returned = ₹240
            ReturnRecord ret = new ReturnRecord(null, savedS1.id(), "CN26-27/0001", "2026-27", testDate, PaymentMode.CASH, Money.ofRupees(240), "Customer change of mind", null, ownerUserId, List.of());
            ReturnRecord savedRet = returnRepository.insertReturn(conn, ret);
            returnRepository.insertReturnItem(conn, new ReturnItemRecord(null, savedRet.id(), savedSi1.id(), v1.id(), Quantity.of(2), com.shopbilling.model.ReturnCondition.SELLABLE, Money.ofRupees(240), "Customer change of mind"));
        });
    }

    @Test
    @DisplayName("Staff user is prohibited from viewing Brand/Category margin analytics")
    void testStaffUserProhibited() {
        FinancialYear fy = FinancialYear.of(2026);
        assertThrows(ValidationException.class, () ->
                analyticsService.generateAnalytics(fy, Quarter.Q1, staffUserId)
        );
    }

    @Test
    @DisplayName("Owner successfully generates Brand and Category performance with return rates and margins")
    void testGenerateAnalytics() {
        FinancialYear fy = FinancialYear.of(2026);
        CategoryBrandAnalyticsResult report = analyticsService.generateAnalytics(fy, Quarter.Q1, ownerUserId);

        assertNotNull(report);
        assertEquals(2, report.brandSummaries().size());
        assertEquals(2, report.categorySummaries().size());

        // Verify Brand Rows
        // Anchor: 2 units, gross 3600, 0 returns -> net 3600
        BrandPerformanceRow anchorRow = report.brandSummaries().stream()
                .filter(b -> b.brandName().equalsIgnoreCase("Anchor"))
                .findFirst().orElseThrow();
        assertEquals(2, anchorRow.unitsSold());
        assertEquals(0, anchorRow.unitsReturned());
        assertEquals(Money.ofRupees(3600), anchorRow.grossSales());
        assertEquals(Money.ofRupees(3600), anchorRow.netSales());
        assertEquals(0.0, anchorRow.returnRatePercent());

        // Havells: 10 units sold, 2 units returned -> net 960 (1200 - 240)
        BrandPerformanceRow havellsRow = report.brandSummaries().stream()
                .filter(b -> b.brandName().equalsIgnoreCase("Havells"))
                .findFirst().orElseThrow();
        assertEquals(10, havellsRow.unitsSold());
        assertEquals(2, havellsRow.unitsReturned());
        assertEquals(Money.ofRupees(1200), havellsRow.grossSales());
        assertEquals(Money.ofRupees(240), havellsRow.returnsValue());
        assertEquals(Money.ofRupees(960), havellsRow.netSales());
        assertEquals(20.0, havellsRow.returnRatePercent()); // 2 returned / 10 sold = 20%

        // Verify Category Rows
        CategoryPerformanceRow switchRow = report.categorySummaries().stream()
                .filter(c -> c.categoryName().equalsIgnoreCase("Modular Switches"))
                .findFirst().orElseThrow();
        assertEquals(10, switchRow.unitsSold());
        assertEquals(2, switchRow.unitsReturned());
        assertEquals(Money.ofRupees(960), switchRow.netSales());

        // Total Figures
        assertEquals(Money.ofRupees(4800), report.totalGrossSales()); // 3600 + 1200
        assertEquals(Money.ofRupees(4560), report.totalNetSales()); // 4800 - 240
    }

    @Test
    @DisplayName("exportToCsv generates valid CSV report with Brand and Category breakdown")
    void testExportCsv() {
        FinancialYear fy = FinancialYear.of(2026);
        CategoryBrandAnalyticsResult report = analyticsService.generateAnalytics(fy, Quarter.Q1, ownerUserId);

        String csv = analyticsService.exportToCsvString(report);
        assertNotNull(csv);
        assertTrue(csv.contains("BRAND PERFORMANCE & CONTRIBUTION MARGIN"));
        assertTrue(csv.contains("CATEGORY PERFORMANCE & RETURN RATE"));
        assertTrue(csv.contains("Anchor"));
        assertTrue(csv.contains("Havells"));
        assertTrue(csv.contains("Ceiling Fans"));
        assertTrue(csv.contains("Modular Switches"));
    }
}
