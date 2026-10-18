package com.shopbilling.ui;

import com.shopbilling.db.*;
import com.shopbilling.model.User;
import com.shopbilling.service.*;
import com.shopbilling.service.pdf.BillPdfService;
import com.shopbilling.service.pdf.CustomerStatementPdfService;
import com.shopbilling.service.printer.ThermalPrinterService;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Central application context and service registry for the JavaFX UI layer.
 * Wires together repositories, business services, and current user session.
 */
public class AppContext {

    private final ConnectionFactory connectionFactory;
    private final TransactionHelper transactionHelper;
    private final IstClock clock;

    // Repositories
    private final UserRepository userRepository;
    private final AuditLogRepository auditLogRepository;
    private final BrandRepository brandRepository;
    private final CategoryRepository categoryRepository;
    private final UnitRepository unitRepository;
    private final ProductRepository productRepository;
    private final ProductVariantRepository productVariantRepository;
    private final CustomerRepository customerRepository;
    private final SupplierRepository supplierRepository;
    private final StockRepository stockRepository;
    private final PurchaseRepository purchaseRepository;
    private final BillCounterRepository billCounterRepository;
    private final CreditNoteCounterRepository creditNoteCounterRepository;
    private final SaleRepository saleRepository;
    private final PaymentRepository paymentRepository;
    private final ReturnRepository returnRepository;
    private final CustomerLedgerRepository customerLedgerRepository;
    private final SupplierLedgerRepository supplierLedgerRepository;
    private final ExpenseRepository expenseRepository;
    private final DayEndClosingRepository dayEndClosingRepository;
    private final ReportRepository reportRepository;
    private final ProfitRepository profitRepository;
    private final BackupRepository backupRepository;
    private final SettingsRepository settingsRepository;

    // Services
    private final AuditLogService auditLogService;
    private final AuthService authService;
    private final UserService userService;
    private final BrandCategoryService brandCategoryService;
    private final ProductService productService;
    private final StockService stockService;
    private final StockInService stockInService;
    private final CustomerSupplierService customerSupplierService;
    private final PaymentValidationService paymentValidationService;
    private final BillCounterService billCounterService;
    private final GenerateBillService generateBillService;
    private final RegisterSaleService registerSaleService;
    private final ReturnService returnService;
    private final CreditLedgerService creditLedgerService;
    private final ExpenseService expenseService;
    private final DayEndClosingService dayEndClosingService;
    private final ReportService reportService;
    private final ProfitService profitService;
    private final BackupService backupService;
    private final DiagnosticsService diagnosticsService;
    private final BillPdfService billPdfService;
    private final ThermalPrinterService thermalPrinterService;
    private final ScheduledBackupService scheduledBackupService;
    private final ShopSettingsService shopSettingsService;
    private final ConvertRegisteredSaleService convertRegisteredSaleService;
    private final LowStockService lowStockService;
    private final SupplierReturnService supplierReturnService;
    private final StockAdjustmentService stockAdjustmentService;
    private final TechnicianPricingService technicianPricingService;
    private final PaymentAllocationService paymentAllocationService;
    private final CustomerStatementPdfService customerStatementPdfService;
    private final CatalogueSearchService catalogueSearchService;

    private User currentUser;

    public AppContext(DataDirectoryHandler dataDirHandler) {
        Objects.requireNonNull(dataDirHandler, "DataDirectoryHandler cannot be null");
        dataDirHandler.ensureDirectoriesExist();

        DatabaseConfig dbConfig = DatabaseConfig.forDataDirectory(dataDirHandler);
        this.connectionFactory = new ConnectionFactory(dbConfig);

        // Run Flyway migrations
        new FlywayMigrator(connectionFactory).migrate();

        this.transactionHelper = new TransactionHelper(connectionFactory);
        this.clock = IstClock.system();

        // Initialize repositories
        this.userRepository = new UserRepository();
        this.auditLogRepository = new AuditLogRepository();
        this.brandRepository = new BrandRepository();
        this.categoryRepository = new CategoryRepository();
        this.unitRepository = new UnitRepository();
        this.productRepository = new ProductRepository();
        this.productVariantRepository = new ProductVariantRepository();
        this.customerRepository = new CustomerRepository();
        this.supplierRepository = new SupplierRepository();
        this.stockRepository = new StockRepository();
        this.purchaseRepository = new PurchaseRepository();
        this.billCounterRepository = new BillCounterRepository();
        this.creditNoteCounterRepository = new CreditNoteCounterRepository();
        this.saleRepository = new SaleRepository();
        this.paymentRepository = new PaymentRepository();
        this.returnRepository = new ReturnRepository();
        this.customerLedgerRepository = new CustomerLedgerRepository();
        this.supplierLedgerRepository = new SupplierLedgerRepository();
        this.expenseRepository = new ExpenseRepository();
        this.dayEndClosingRepository = new DayEndClosingRepository();
        this.reportRepository = new ReportRepository();
        this.profitRepository = new ProfitRepository();
        this.backupRepository = new BackupRepository();
        this.settingsRepository = new SettingsRepository();

        // Initialize services
        this.auditLogService = new AuditLogService(transactionHelper, auditLogRepository, userRepository);
        this.authService = new AuthService(transactionHelper, userRepository, auditLogService);
        this.userService = new UserService(transactionHelper, userRepository, auditLogService);
        this.brandCategoryService = new BrandCategoryService(transactionHelper, brandRepository, categoryRepository);
        this.productService = new ProductService(transactionHelper, productRepository, productVariantRepository,
                brandRepository, categoryRepository, unitRepository);
        this.stockService = new StockService(transactionHelper, stockRepository, settingsRepository, productVariantRepository);
        this.customerSupplierService = new CustomerSupplierService(transactionHelper, customerRepository, supplierRepository);
        this.paymentValidationService = new PaymentValidationService(transactionHelper, customerRepository);
        this.billCounterService = new BillCounterService(transactionHelper, billCounterRepository);

        this.generateBillService = new GenerateBillService(
                transactionHelper,
                saleRepository,
                billCounterService,
                paymentRepository,
                paymentValidationService,
                stockService,
                customerLedgerRepository,
                customerRepository,
                productVariantRepository,
                productRepository,
                unitRepository,
                settingsRepository,
                clock
        );

        this.registerSaleService = new RegisterSaleService(
                transactionHelper,
                saleRepository,
                paymentRepository,
                paymentValidationService,
                stockService,
                customerLedgerRepository,
                customerRepository,
                productVariantRepository,
                productRepository,
                unitRepository,
                settingsRepository,
                clock
        );

        this.stockInService = new StockInService(
                transactionHelper,
                purchaseRepository,
                stockService,
                supplierRepository,
                supplierLedgerRepository,
                productVariantRepository
        );

        this.returnService = new ReturnService(
                transactionHelper,
                returnRepository,
                creditNoteCounterRepository,
                saleRepository,
                stockService,
                customerLedgerRepository,
                customerRepository,
                settingsRepository,
                clock
        );

        this.creditLedgerService = new CreditLedgerService(
                transactionHelper,
                customerLedgerRepository,
                supplierLedgerRepository,
                customerRepository,
                supplierRepository,
                saleRepository,
                clock
        );

        this.expenseService = new ExpenseService(transactionHelper, expenseRepository, clock);

        this.backupService = new BackupService(
                connectionFactory,
                transactionHelper,
                backupRepository,
                settingsRepository,
                userRepository,
                auditLogService,
                dataDirHandler.getBackupDirectory(),
                dataDirHandler.getDatabasePath(),
                clock
        );

        this.dayEndClosingService = new DayEndClosingService(transactionHelper, dayEndClosingRepository, expenseRepository, backupService, clock);
        this.reportService = new ReportService(transactionHelper, reportRepository, clock);
        this.profitService = new ProfitService(transactionHelper, profitRepository, expenseRepository, clock);

        Path logDir = dataDirHandler.getDataDirectory().getParent() != null
                ? dataDirHandler.getDataDirectory().getParent().resolve("logs")
                : dataDirHandler.getDataDirectory().resolve("logs");

        this.diagnosticsService = new DiagnosticsService(backupService, dataDirHandler.getDatabasePath(), logDir, clock);
        this.billPdfService = new BillPdfService(
                transactionHelper,
                saleRepository,
                paymentRepository,
                customerRepository,
                productVariantRepository,
                productRepository,
                brandRepository,
                unitRepository,
                settingsRepository
        );

        this.thermalPrinterService = new ThermalPrinterService(
                transactionHelper,
                saleRepository,
                paymentRepository,
                customerRepository,
                productVariantRepository,
                productRepository,
                settingsRepository
        );

        this.scheduledBackupService = new ScheduledBackupService(
                backupService,
                backupRepository,
                transactionHelper,
                settingsRepository,
                auditLogService,
                clock
        );

        this.shopSettingsService = new ShopSettingsService(
                transactionHelper,
                settingsRepository,
                userRepository,
                auditLogService
        );

        this.convertRegisteredSaleService = new ConvertRegisteredSaleService(
                transactionHelper,
                saleRepository,
                paymentRepository,
                billCounterService,
                userRepository,
                auditLogService,
                clock
        );

        this.lowStockService = new LowStockService(
                transactionHelper,
                stockRepository
        );

        this.supplierReturnService = new SupplierReturnService(
                transactionHelper,
                supplierRepository,
                supplierLedgerRepository,
                productVariantRepository,
                stockService,
                userRepository,
                auditLogService,
                clock
        );

        this.stockAdjustmentService = new StockAdjustmentService(
                transactionHelper,
                stockRepository,
                productVariantRepository,
                stockService,
                userRepository,
                auditLogService
        );

        this.technicianPricingService = new TechnicianPricingService(
                transactionHelper,
                settingsRepository,
                productVariantRepository,
                profitRepository,
                customerRepository,
                userRepository,
                auditLogService
        );

        this.paymentAllocationService = new PaymentAllocationService(
                transactionHelper,
                customerRepository,
                customerLedgerRepository,
                saleRepository
        );

        this.customerStatementPdfService = new CustomerStatementPdfService(
                creditLedgerService,
                transactionHelper,
                settingsRepository,
                clock
        );

        this.catalogueSearchService = new CatalogueSearchService(
                transactionHelper,
                stockRepository
        );
    }

    public ConnectionFactory getConnectionFactory() { return connectionFactory; }
    public TransactionHelper getTransactionHelper() { return transactionHelper; }
    public IstClock getClock() { return clock; }

    public AuthService getAuthService() { return authService; }
    public UserService getUserService() { return userService; }
    public AuditLogService getAuditLogService() { return auditLogService; }
    public BrandCategoryService getBrandCategoryService() { return brandCategoryService; }
    public ProductService getProductService() { return productService; }
    public StockService getStockService() { return stockService; }
    public StockInService getStockInService() { return stockInService; }
    public CustomerSupplierService getCustomerSupplierService() { return customerSupplierService; }
    public GenerateBillService getGenerateBillService() { return generateBillService; }
    public RegisterSaleService getRegisterSaleService() { return registerSaleService; }
    public ReturnService getReturnService() { return returnService; }
    public CreditLedgerService getCreditLedgerService() { return creditLedgerService; }
    public ExpenseService getExpenseService() { return expenseService; }
    public DayEndClosingService getDayEndClosingService() { return dayEndClosingService; }
    public ReportService getReportService() { return reportService; }
    public ProfitService getProfitService() { return profitService; }
    public BackupService getBackupService() { return backupService; }
    public DiagnosticsService getDiagnosticsService() { return diagnosticsService; }
    public BillPdfService getBillPdfService() { return billPdfService; }
    public ThermalPrinterService getThermalPrinterService() { return thermalPrinterService; }
    public ScheduledBackupService getScheduledBackupService() { return scheduledBackupService; }
    public ShopSettingsService getShopSettingsService() { return shopSettingsService; }
    public ConvertRegisteredSaleService getConvertRegisteredSaleService() { return convertRegisteredSaleService; }
    public LowStockService getLowStockService() { return lowStockService; }
    public SupplierReturnService getSupplierReturnService() { return supplierReturnService; }
    public StockAdjustmentService getStockAdjustmentService() { return stockAdjustmentService; }
    public TechnicianPricingService getTechnicianPricingService() { return technicianPricingService; }
    public PaymentAllocationService getPaymentAllocationService() { return paymentAllocationService; }
    public CustomerStatementPdfService getCustomerStatementPdfService() { return customerStatementPdfService; }
    public CatalogueSearchService getCatalogueSearchService() { return catalogueSearchService; }
    public SettingsRepository getSettingsRepository() { return settingsRepository; }

    public User getCurrentUser() { return currentUser; }
    public void setCurrentUser(User user) { this.currentUser = user; }
    public boolean isLoggedIn() { return currentUser != null; }
    public boolean isOwner() { return currentUser != null && currentUser.isOwner(); }
}
