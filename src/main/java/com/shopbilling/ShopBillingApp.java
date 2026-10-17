package com.shopbilling;

import com.shopbilling.db.BrandRepository;
import com.shopbilling.db.CategoryRepository;
import com.shopbilling.db.ConnectionFactory;
import com.shopbilling.db.DataDirectoryHandler;
import com.shopbilling.db.DatabaseConfig;
import com.shopbilling.db.FlywayMigrator;
import com.shopbilling.db.ProductRepository;
import com.shopbilling.db.ProductVariantRepository;
import com.shopbilling.db.TransactionHelper;
import com.shopbilling.db.UnitRepository;
import com.shopbilling.model.FinancialYear;
import com.shopbilling.model.Quarter;
import com.shopbilling.service.BrandCategoryService;
import com.shopbilling.service.IstClock;
import com.shopbilling.service.ProductService;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Separator;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.time.format.DateTimeFormatter;

/**
 * Main JavaFX application entry point for the Shop Billing Desktop App.
 * Displays live database and system status while foundation & service modules (M01-M26)
 * are being completed, before full UI shell screens (M27-M37) are mounted.
 */
public class ShopBillingApp extends Application {

    private static final Logger logger = LoggerFactory.getLogger(ShopBillingApp.class);

    private ConnectionFactory connectionFactory;
    private TransactionHelper transactionHelper;
    private BrandCategoryService brandCategoryService;
    private ProductService productService;
    private IstClock istClock;

    @Override
    public void start(Stage primaryStage) {
        logger.info("Initializing Shop Billing Desktop App UI...");

        VBox root = new VBox(15);
        root.setPadding(new Insets(25));
        root.setAlignment(Pos.TOP_CENTER);
        root.setStyle("-fx-background-color: #f8fafc;");

        // Header
        Label headerTitle = new Label("Shop Billing Desktop App");
        headerTitle.setStyle("-fx-font-size: 22px; -fx-font-weight: bold; -fx-text-fill: #1e293b;");

        Label subHeader = new Label("Single Electrical Goods Shop • Offline Billing & Stock System");
        subHeader.setStyle("-fx-font-size: 13px; -fx-text-fill: #64748b;");

        // Status Card
        VBox card = new VBox(12);
        card.setPadding(new Insets(20));
        card.setStyle("-fx-background-color: #ffffff; -fx-background-radius: 8px; " +
                "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.06), 10, 0, 0, 4); " +
                "-fx-border-color: #e2e8f0; -fx-border-radius: 8px;");

        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setMaxSize(40, 40);

        Label statusTitle = new Label("Initializing Database & Core Services...");
        statusTitle.setStyle("-fx-font-size: 15px; -fx-font-weight: bold; -fx-text-fill: #334155;");

        VBox loadingBox = new VBox(10, spinner, statusTitle);
        loadingBox.setAlignment(Pos.CENTER);
        card.getChildren().add(loadingBox);

        root.getChildren().addAll(headerTitle, subHeader, new Separator(), card);

        Scene scene = new Scene(root, 620, 480);
        primaryStage.setTitle("Shop Billing Desktop App");
        primaryStage.setScene(scene);
        primaryStage.show();

        // Run database initialization asynchronously off the JavaFX Application Thread
        Task<SystemStatus> initTask = new Task<>() {
            @Override
            protected SystemStatus call() throws Exception {
                logger.info("Starting background system initialization...");
                DataDirectoryHandler dataDirHandler = new DataDirectoryHandler();
                dataDirHandler.ensureDirectoriesExist();

                DatabaseConfig dbConfig = DatabaseConfig.forDataDirectory(dataDirHandler);
                connectionFactory = new ConnectionFactory(dbConfig);

                // Run Flyway migrations
                FlywayMigrator migrator = new FlywayMigrator(connectionFactory);
                migrator.migrate();

                transactionHelper = new TransactionHelper(connectionFactory);
                BrandRepository brandRepo = new BrandRepository();
                CategoryRepository catRepo = new CategoryRepository();
                UnitRepository unitRepo = new UnitRepository();
                ProductRepository prodRepo = new ProductRepository();
                ProductVariantRepository variantRepo = new ProductVariantRepository();

                brandCategoryService = new BrandCategoryService(transactionHelper, brandRepo, catRepo);
                productService = new ProductService(transactionHelper, prodRepo, variantRepo, brandRepo, catRepo, unitRepo);
                istClock = IstClock.system();

                // Collect status statistics
                String journalMode;
                boolean fksEnabled;
                try (Connection conn = connectionFactory.getConnection()) {
                    journalMode = connectionFactory.getActiveJournalMode(conn);
                    fksEnabled = connectionFactory.verifyForeignKeysEnabled(conn);
                }

                int brandCount = brandCategoryService.getAllBrands().size();
                int categoryCount = brandCategoryService.getAllCategories().size();
                int unitCount = productService.getAllUnits().size();
                FinancialYear currentFy = FinancialYear.ofDate(istClock.today());
                Quarter currentQuarter = currentFy.getQuarterOf(istClock.today());

                return new SystemStatus(
                        dataDirHandler.getDatabasePath().toString(),
                        journalMode,
                        fksEnabled,
                        currentFy.getLabel(),
                        currentQuarter.getDisplayLabel(),
                        istClock.now().format(DateTimeFormatter.ofPattern("dd-MMM-yyyy HH:mm:ss")),
                        brandCount,
                        categoryCount,
                        unitCount
                );
            }
        };

        initTask.setOnSucceeded(event -> {
            SystemStatus status = initTask.getValue();
            card.getChildren().clear();

            Label readyBadge = new Label("● System Core Online (Modules M01 – M06 Loaded)");
            readyBadge.setStyle("-fx-background-color: #dcfce7; -fx-text-fill: #15803d; " +
                    "-fx-font-weight: bold; -fx-padding: 4 10; -fx-background-radius: 4;");

            GridPane grid = new GridPane();
            grid.setHgap(15);
            grid.setVgap(10);
            grid.setPadding(new Insets(10, 0, 10, 0));

            addInfoRow(grid, 0, "SQLite Database:", status.dbPath());
            addInfoRow(grid, 1, "WAL Mode & FKs:", "Journal: " + status.journalMode().toUpperCase() + " • FK Constraints: " + (status.fksEnabled() ? "ACTIVE" : "OFF"));
            addInfoRow(grid, 2, "Schema Version:", "V1 (All 33 PRD Tables Created)");
            addInfoRow(grid, 3, "IST System Clock:", status.currentTimeIst() + " (Asia/Kolkata)");
            addInfoRow(grid, 4, "Financial Year:", status.financialYear() + " • " + status.quarter());
            addInfoRow(grid, 5, "Catalogue Loaded:", status.brands() + " Brands, " + status.categories() + " Categories, " + status.units() + " Standard Units");

            Label notice = new Label("Note: Interactive billing and product screens are built in Modules M27–M37\nonce all core data and sales services are completed and verified.");
            notice.setStyle("-fx-font-size: 12px; -fx-text-fill: #64748b; -fx-font-style: italic;");

            card.getChildren().addAll(readyBadge, grid, new Separator(), notice);
        });

        initTask.setOnFailed(event -> {
            Throwable ex = initTask.getException();
            logger.error("Startup initialization failed", ex);
            card.getChildren().clear();

            Label errorBadge = new Label("Initialization Error");
            errorBadge.setStyle("-fx-background-color: #fee2e2; -fx-text-fill: #b91c1c; " +
                    "-fx-font-weight: bold; -fx-padding: 4 10; -fx-background-radius: 4;");

            Label errorDetail = new Label(ex != null ? ex.getMessage() : "Unknown error");
            errorDetail.setStyle("-fx-text-fill: #b91c1c; -fx-font-size: 12px;");

            card.getChildren().addAll(errorBadge, errorDetail);
        });

        Thread thread = new Thread(initTask, "app-startup-worker");
        thread.setDaemon(true);
        thread.start();
    }

    private void addInfoRow(GridPane grid, int row, String label, String value) {
        Label l = new Label(label);
        l.setStyle("-fx-font-weight: bold; -fx-text-fill: #475569; -fx-min-width: 140px;");
        Label v = new Label(value);
        v.setStyle("-fx-text-fill: #0f172a;");
        grid.add(l, 0, row);
        grid.add(v, 1, row);
    }

    public static void main(String[] args) {
        logger.info("Starting Shop Billing Desktop App...");
        launch(args);
    }

    record SystemStatus(
            String dbPath,
            String journalMode,
            boolean fksEnabled,
            String financialYear,
            String quarter,
            String currentTimeIst,
            int brands,
            int categories,
            int units
    ) {}
}
