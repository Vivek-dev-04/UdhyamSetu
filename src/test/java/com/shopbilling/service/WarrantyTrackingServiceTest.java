package com.shopbilling.service;

import com.shopbilling.db.BrandRepository;
import com.shopbilling.db.CategoryRepository;
import com.shopbilling.db.ConnectionFactory;
import com.shopbilling.db.CustomerRepository;
import com.shopbilling.db.DatabaseConfig;
import com.shopbilling.db.FlywayMigrator;
import com.shopbilling.db.ProductRepository;
import com.shopbilling.db.ProductVariantRepository;
import com.shopbilling.db.SaleRepository;
import com.shopbilling.db.TransactionHelper;
import com.shopbilling.db.UserRepository;
import com.shopbilling.db.WarrantyRepository;
import com.shopbilling.model.Brand;
import com.shopbilling.model.Category;
import com.shopbilling.model.Customer;
import com.shopbilling.model.CustomerType;
import com.shopbilling.model.Money;
import com.shopbilling.model.Product;
import com.shopbilling.model.ProductVariant;
import com.shopbilling.model.Quantity;
import com.shopbilling.model.Sale;
import com.shopbilling.model.SaleItem;
import com.shopbilling.model.SaleMode;
import com.shopbilling.model.User;
import com.shopbilling.model.UserRole;
import com.shopbilling.model.WarrantyRecord;
import com.shopbilling.model.WarrantyStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("WarrantyTrackingService Tests (Appliance Serial Number & Warranty Lifecycle)")
class WarrantyTrackingServiceTest {

    @TempDir
    Path tempDir;

    private ConnectionFactory connectionFactory;
    private TransactionHelper transactionHelper;
    private WarrantyRepository warrantyRepository;
    private SaleRepository saleRepository;
    private ProductVariantRepository variantRepository;
    private IstClock clock;

    private WarrantyTrackingService warrantyService;

    private long saleId;
    private long geyserVariantId;
    private long fanVariantId;
    private long ownerUserId;

    private final LocalDate saleDate = LocalDate.of(2026, 4, 10);

    @BeforeEach
    void setUp() throws IOException {
        Path dbFile = tempDir.resolve("warranty_test.db");
        DatabaseConfig config = DatabaseConfig.forFile(dbFile);
        connectionFactory = new ConnectionFactory(config);
        new FlywayMigrator(connectionFactory).migrate();

        transactionHelper = new TransactionHelper(connectionFactory);
        warrantyRepository = new WarrantyRepository();
        saleRepository = new SaleRepository();
        variantRepository = new ProductVariantRepository();
        ProductRepository productRepository = new ProductRepository();
        BrandRepository brandRepository = new BrandRepository();
        CategoryRepository categoryRepository = new CategoryRepository();
        UserRepository userRepository = new UserRepository();
        CustomerRepository customerRepository = new CustomerRepository();

        clock = IstClock.fixedAtStartOfDay(saleDate);
        warrantyService = new WarrantyTrackingService(transactionHelper, warrantyRepository, saleRepository, variantRepository, clock);

        transactionHelper.executeVoid(conn -> {
            User owner = userRepository.insert(conn, new User(null, "owner", "pass", UserRole.OWNER, "Shop Owner", true, null));
            ownerUserId = owner.id();

            Customer customer = customerRepository.insert(conn, new Customer(
                    null, "Suresh Sharma", "9829123456", CustomerType.RETAIL, "Shastri Nagar", null, null, null
            ));

            Brand havells = brandRepository.insert(conn, "Havells");
            Category appliance = categoryRepository.insert(conn, "Water Heaters");
            Category fanCat = categoryRepository.insert(conn, "Fans");

            Product geyser = productRepository.insert(conn, new Product(null, havells.id(), appliance.id(), "Monza Geyser 15L", "PIECE", 2L, true, null));
            ProductVariant varGeyser = variantRepository.insert(conn, new ProductVariant(null, geyser.id(), "15 Litre Ivory", Money.ofRupees(8500), Money.ofRupees(7800), "HVL-GYS-15L", true, null));
            geyserVariantId = varGeyser.id();

            Product fan = productRepository.insert(conn, new Product(null, havells.id(), fanCat.id(), "Ceiling Fan", "PIECE", 5L, true, null));
            ProductVariant varFan = variantRepository.insert(conn, new ProductVariant(null, fan.id(), "1200mm White", Money.ofRupees(2200), Money.ofRupees(1900), "HVL-FAN-WHT", true, null));
            fanVariantId = varFan.id();

            // Sale containing ONLY the Geyser
            Sale sale = new Sale(
                    null, SaleMode.BILL, "2026-27", "FY26-27/0010", 10, customer.id(),
                    saleDate, null, Money.ofRupees(7800), Money.ZERO, Money.ofRupees(7800),
                    ownerUserId, List.of(), List.of()
            );
            Sale savedSale = saleRepository.insertSale(conn, sale);
            saleId = savedSale.id();

            SaleItem item = new SaleItem(null, saleId, geyserVariantId, Quantity.of(1), Money.ofRupees(7800), Money.ZERO, Money.ofRupees(7800));
            saleRepository.insertSaleItem(conn, item);
        });
    }

    @Test
    @DisplayName("registerWarranty successfully binds serial number and calculates warranty period")
    void testRegisterWarranty() {
        WarrantyRecord warranty = warrantyService.registerWarranty(saleId, geyserVariantId, "HVL-SN-99887766", 24, ownerUserId);

        assertNotNull(warranty);
        assertEquals("HVL-SN-99887766", warranty.serialNumber());
        assertEquals(24, warranty.warrantyMonths());
        assertEquals(saleDate, warranty.warrantyStartDate());
        assertEquals(saleDate.plusMonths(24), warranty.warrantyEndDate());
        assertEquals(WarrantyStatus.ACTIVE, warranty.status());
        assertEquals("Suresh Sharma", warranty.customerName());
        assertEquals("9829123456", warranty.customerPhone());
        assertEquals("Monza Geyser 15L", warranty.productName());
    }

    @Test
    @DisplayName("lookupWarranty finds registered item and auto-expires when date passes warranty end")
    void testLookupWarrantyAndAutoExpire() {
        warrantyService.registerWarranty(saleId, geyserVariantId, "SN-AUTO-EXPIRE-1", 12, ownerUserId);

        // 1. Within warranty: still ACTIVE
        Optional<WarrantyRecord> activeOpt = warrantyService.lookupWarranty("SN-AUTO-EXPIRE-1");
        assertTrue(activeOpt.isPresent());
        assertEquals(WarrantyStatus.ACTIVE, activeOpt.get().status());
        assertTrue(activeOpt.get().isCurrentlyWithinWarranty(saleDate));

        // 2. Fast forward clock by 15 months: status auto-transitions to EXPIRED
        IstClock futureClock = IstClock.fixedAtStartOfDay(saleDate.plusMonths(15));
        WarrantyTrackingService futureService = new WarrantyTrackingService(transactionHelper, warrantyRepository, saleRepository, variantRepository, futureClock);

        Optional<WarrantyRecord> expiredOpt = futureService.lookupWarranty("SN-AUTO-EXPIRE-1");
        assertTrue(expiredOpt.isPresent());
        assertEquals(WarrantyStatus.EXPIRED, expiredOpt.get().status());
        assertFalse(expiredOpt.get().isCurrentlyWithinWarranty(saleDate.plusMonths(15)));
    }

    @Test
    @DisplayName("Duplicate serial number registration is strictly forbidden")
    void testDuplicateSerialThrows() {
        warrantyService.registerWarranty(saleId, geyserVariantId, "UNIQUE-SN-1234", 12, ownerUserId);

        assertThrows(DuplicateEntityException.class, () ->
                warrantyService.registerWarranty(saleId, geyserVariantId, "unique-sn-1234", 12, ownerUserId)
        );
    }

    @Test
    @DisplayName("Registering warranty for a product variant not in the sale throws ValidationException")
    void testVariantNotInSaleThrows() {
        // Fan was NOT part of this sale
        assertThrows(ValidationException.class, () ->
                warrantyService.registerWarranty(saleId, fanVariantId, "FAN-SN-0001", 24, ownerUserId)
        );
    }

    @Test
    @DisplayName("processWarrantyClaim updates status to CLAIMED with notes")
    void testProcessWarrantyClaim() {
        warrantyService.registerWarranty(saleId, geyserVariantId, "SN-CLAIM-TEST", 24, ownerUserId);

        WarrantyRecord claimed = warrantyService.processWarrantyClaim("SN-CLAIM-TEST", WarrantyStatus.CLAIMED, "Heating coil replaced under manufacturer warranty", ownerUserId);

        assertEquals(WarrantyStatus.CLAIMED, claimed.status());
        assertEquals("Heating coil replaced under manufacturer warranty", claimed.claimNotes());
    }

    @Test
    @DisplayName("getWarrantiesForSale retrieves all warranties for invoice")
    void testGetWarrantiesForSale() {
        warrantyService.registerWarranty(saleId, geyserVariantId, "SN-SALE-1", 24, ownerUserId);

        List<WarrantyRecord> list = warrantyService.getWarrantiesForSale(saleId);
        assertEquals(1, list.size());
        assertEquals("SN-SALE-1", list.getFirst().serialNumber());
    }
}
