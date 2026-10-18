package com.shopbilling.ui;

import com.shopbilling.model.BackupRecord;
import com.shopbilling.model.BackupType;
import com.shopbilling.model.FinancialYear;
import com.shopbilling.model.Quarter;
import com.shopbilling.model.SaleMode;
import com.shopbilling.model.User;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;

import java.io.File;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.Optional;

/**
 * Main Home Screen adhering strictly to PRD Section 7.1 (FR-1, FR-2, FR-3).
 * Shows exactly three large buttons: Generate Bill, Register Sale, Sales.
 * Secondary functions are accessed via the top menu bar.
 * Bottom bar shows the last successful backup time in red if older than 24 hours.
 */
public class HomeScreen {

    private final ScreenManager screenManager;
    private final AppContext context;

    public HomeScreen(ScreenManager screenManager) {
        this.screenManager = Objects.requireNonNull(screenManager, "ScreenManager cannot be null");
        this.context = screenManager.getContext();
    }

    public Parent getView() {
        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color: #f8fafc;");

        // 1. TOP SECTION: Menu Bar + User Header
        VBox topBox = new VBox();
        MenuBar menuBar = buildMenuBar();
        HBox headerBar = buildHeaderBar();
        topBox.getChildren().addAll(menuBar, headerBar);
        root.setTop(topBox);

        // 2. CENTER SECTION: Exactly Three Large Buttons (FR-1)
        VBox centerBox = new VBox(25);
        centerBox.setAlignment(Pos.CENTER);
        centerBox.setPadding(new Insets(40, 50, 40, 50));

        Label sectionTitle = new Label("Counter Operations");
        sectionTitle.setStyle("-fx-font-size: 20px; -fx-font-weight: bold; -fx-text-fill: #1e293b;");

        HBox buttonContainer = new HBox(30);
        buttonContainer.setAlignment(Pos.CENTER);

        // Button 1: Generate Bill (FR-1)
        Button generateBillBtn = createHeroButton(
                "Generate Bill",
                "Official A4 / Thermal bill with sequential bill number\n(Counts in turnover, deducts stock)",
                "#1e40af",
                "#2563eb",
                "F1"
        );
        generateBillBtn.setOnAction(e -> screenManager.showBilling(SaleMode.BILL));

        // Button 2: Register Sale (FR-1)
        Button registerSaleBtn = createHeroButton(
                "Register Sale",
                "Unbilled counter sale without printed invoice\n(Counts in turnover, deducts stock)",
                "#065f46",
                "#059669",
                "F2"
        );
        registerSaleBtn.setOnAction(e -> screenManager.showBilling(SaleMode.REGISTERED));

        // Button 3: Sales (FR-1)
        Button salesBtn = createHeroButton(
                "Sales & Returns",
                "View sales history, combinable filters, CSV export,\nconvert registered sales to bill, and customer returns",
                "#334155",
                "#475569",
                "F3"
        );
        salesBtn.setOnAction(e -> screenManager.showSales());

        buttonContainer.getChildren().addAll(generateBillBtn, registerSaleBtn, salesBtn);
        centerBox.getChildren().addAll(sectionTitle, buttonContainer);
        root.setCenter(centerBox);

        // 3. BOTTOM SECTION: Last Backup Status & Health Check (FR-3)
        HBox statusBar = buildStatusBar();
        root.setBottom(statusBar);

        // Keyboard shortcuts for hero buttons
        root.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.F1) {
                screenManager.showBilling(SaleMode.BILL);
            } else if (event.getCode() == KeyCode.F2) {
                screenManager.showBilling(SaleMode.REGISTERED);
            } else if (event.getCode() == KeyCode.F3) {
                screenManager.showSales();
            }
        });

        return root;
    }

    private MenuBar buildMenuBar() {
        MenuBar menuBar = new MenuBar();
        menuBar.setStyle("-fx-background-color: #ffffff; -fx-border-color: #e2e8f0; -fx-border-width: 0 0 1 0;");

        // Menu: Inventory & Products (FR-2)
        Menu inventoryMenu = new Menu("Inventory");
        MenuItem stockItem = new MenuItem("Stock & Low-Stock Alerts");
        stockItem.setOnAction(e -> screenManager.showStock());

        MenuItem recordStockInItem = new MenuItem("Record Stock In (Distributor Purchase)");
        recordStockInItem.setOnAction(e -> screenManager.showStock());

        MenuItem warrantyItem = new MenuItem("Appliance Warranties & Serial Numbers");
        warrantyItem.setOnAction(e -> screenManager.showWarrantyManagement());

        inventoryMenu.getItems().addAll(stockItem, recordStockInItem, warrantyItem);

        // Menu: Ledgers (FR-2)
        Menu ledgersMenu = new Menu("Ledgers");
        MenuItem customerDuesItem = new MenuItem("Customer Dues & Aging (0-30, 31-60, 61-90, 90+ days)");
        customerDuesItem.setOnAction(e -> screenManager.showCreditLedger());

        MenuItem supplierLedgerItem = new MenuItem("Supplier Ledgers & Payables");
        supplierLedgerItem.setOnAction(e -> screenManager.showCreditLedger());

        ledgersMenu.getItems().addAll(customerDuesItem, supplierLedgerItem);

        // Menu: Accounts & Day-End (FR-2)
        Menu accountsMenu = new Menu("Accounts");
        MenuItem expenseItem = new MenuItem("Record Expense");
        expenseItem.setOnAction(e -> screenManager.showExpensesAndClosing());

        MenuItem dayEndClosingItem = new MenuItem("Day-End Cash Closing");
        dayEndClosingItem.setOnAction(e -> screenManager.showExpensesAndClosing());

        accountsMenu.getItems().addAll(expenseItem, dayEndClosingItem);

        // Menu: Reports & Profit (FR-2, FR-37)
        Menu reportsMenu = new Menu("Reports");
        MenuItem salesReportItem = new MenuItem("Sales Reports & CSV Export");
        salesReportItem.setOnAction(e -> screenManager.showReportsAndProfit());

        MenuItem profitReportItem = new MenuItem("Owner Profit View (Weighted Avg COGS)");
        profitReportItem.setOnAction(e -> screenManager.showReportsAndProfit());
        if (!context.isOwner()) {
            profitReportItem.setDisable(true);
        }

        reportsMenu.getItems().addAll(salesReportItem, profitReportItem);

        // Menu: Settings & Backup (FR-2, FR-46, FR-50)
        Menu adminMenu = new Menu("Settings & Tools");
        MenuItem settingsItem = new MenuItem("Shop Settings");
        settingsItem.setOnAction(e -> screenManager.showSettingsAndAdmin());

        MenuItem backupItem = new MenuItem("Database Backup & Restore");
        backupItem.setOnAction(e -> screenManager.showSettingsAndAdmin());

        MenuItem diagnosticsItem = new MenuItem("Export Diagnostics ZIP");
        diagnosticsItem.setOnAction(e -> exportDiagnostics());

        adminMenu.getItems().addAll(settingsItem, backupItem, new SeparatorMenuItem(), diagnosticsItem);

        menuBar.getMenus().addAll(inventoryMenu, ledgersMenu, accountsMenu, reportsMenu, adminMenu);
        return menuBar;
    }

    private HBox buildHeaderBar() {
        HBox header = new HBox(15);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(12, 25, 12, 25));
        header.setStyle("-fx-background-color: #ffffff; -fx-border-color: #e2e8f0; -fx-border-width: 0 0 1 0;");

        Label brandTitle = new Label("UdhyamSetu");
        brandTitle.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: #1e293b;");

        Label subtitle = new Label("Electrical Goods • Offline Shop Billing");
        subtitle.setStyle("-fx-font-size: 12px; -fx-text-fill: #64748b;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        FinancialYear fy = FinancialYear.ofDate(context.getClock().today());
        Quarter q = fy.getQuarterOf(context.getClock().today());
        Label fyBadge = new Label(fy.getLabel() + " • " + q.name());
        fyBadge.setStyle("-fx-background-color: #e0f2fe; -fx-text-fill: #0369a1; " +
                "-fx-padding: 4 10; -fx-background-radius: 4; -fx-font-size: 12px; -fx-font-weight: bold;");

        User user = context.getCurrentUser();
        String roleStr = user != null ? user.role().name() : "GUEST";
        String nameStr = user != null ? user.fullName() : "User";

        Label userBadge = new Label(nameStr + " (" + roleStr + ")");
        userBadge.setStyle("-fx-background-color: #f1f5f9; -fx-text-fill: #334155; " +
                "-fx-padding: 4 10; -fx-background-radius: 4; -fx-font-size: 12px; -fx-font-weight: bold;");

        Button logoutBtn = new Button("Logout");
        logoutBtn.setStyle("-fx-background-color: transparent; -fx-text-fill: #dc2626; -fx-font-size: 12px;");
        logoutBtn.setOnAction(e -> {
            context.setCurrentUser(null);
            screenManager.showLogin();
        });

        header.getChildren().addAll(brandTitle, subtitle, spacer, fyBadge, userBadge, logoutBtn);
        return header;
    }

    private Button createHeroButton(String title, String description, String bgColor, String hoverColor, String shortcutKey) {
        Button btn = new Button();
        btn.setPrefSize(280, 200);
        btn.setStyle(String.format("-fx-background-color: %s; -fx-background-radius: 12px; " +
                "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.15), 15, 0, 0, 6); -fx-cursor: hand;", bgColor));

        VBox content = new VBox(10);
        content.setAlignment(Pos.CENTER);
        content.setPadding(new Insets(20));

        Label shortcutBadge = new Label(shortcutKey);
        shortcutBadge.setStyle("-fx-background-color: rgba(255,255,255,0.25); -fx-text-fill: white; " +
                "-fx-font-weight: bold; -fx-font-size: 12px; -fx-padding: 2 8; -fx-background-radius: 4px;");

        Label titleLabel = new Label(title);
        titleLabel.setStyle("-fx-font-size: 22px; -fx-font-weight: bold; -fx-text-fill: white;");

        Label descLabel = new Label(description);
        descLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: rgba(255,255,255,0.85); -fx-alignment: center;");
        descLabel.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        descLabel.setWrapText(true);

        content.getChildren().addAll(shortcutBadge, titleLabel, descLabel);
        btn.setGraphic(content);

        btn.setOnMouseEntered(e -> btn.setStyle(String.format("-fx-background-color: %s; -fx-background-radius: 12px; " +
                "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.25), 20, 0, 0, 8); -fx-cursor: hand;", hoverColor)));

        btn.setOnMouseExited(e -> btn.setStyle(String.format("-fx-background-color: %s; -fx-background-radius: 12px; " +
                "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.15), 15, 0, 0, 6); -fx-cursor: hand;", bgColor)));

        return btn;
    }

    private HBox buildStatusBar() {
        HBox statusBar = new HBox(15);
        statusBar.setAlignment(Pos.CENTER_LEFT);
        statusBar.setPadding(new Insets(10, 25, 10, 25));
        statusBar.setStyle("-fx-background-color: #ffffff; -fx-border-color: #e2e8f0; -fx-border-width: 1 0 0 0;");

        // FR-3: Last successful backup time, in red if older than 24 hours
        boolean isOlderThan24h = context.getBackupService().isBackupOlderThan24Hours();
        Optional<BackupRecord> lastBackup = context.getBackupService().getLastSuccessfulBackup();

        Label backupLabel = new Label();
        if (isOlderThan24h) {
            String timeStr = lastBackup.map(BackupRecord::createdAt).orElse("None recorded");
            backupLabel.setText("⚠️ Last Backup: " + timeStr + " (Older than 24 hours - Backup Recommended!)");
            backupLabel.setStyle("-fx-text-fill: #dc2626; -fx-font-weight: bold; -fx-font-size: 12px;");
        } else {
            String timeStr = lastBackup.map(BackupRecord::createdAt).orElse("");
            backupLabel.setText("✓ Last Backup: " + timeStr + " (Healthy)");
            backupLabel.setStyle("-fx-text-fill: #16a34a; -fx-font-weight: bold; -fx-font-size: 12px;");
        }

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button quickBackupBtn = new Button("Backup Now");
        quickBackupBtn.setStyle("-fx-background-color: #f1f5f9; -fx-border-color: #cbd5e1; -fx-border-radius: 4; -fx-font-size: 11px;");
        quickBackupBtn.setOnAction(e -> {
            try {
                BackupRecord b = context.getBackupService().performBackup(BackupType.MANUAL, null);
                Alert alert = new Alert(Alert.AlertType.INFORMATION, "Backup created successfully at:\n" + b.backupFilePath(), ButtonType.OK);
                alert.setTitle("Backup Successful");
                alert.showAndWait();
                screenManager.showHome(); // Refresh view
            } catch (Exception ex) {
                Alert alert = new Alert(Alert.AlertType.ERROR, "Backup failed: " + ex.getMessage(), ButtonType.OK);
                alert.setTitle("Backup Failed");
                alert.showAndWait();
            }
        });

        statusBar.getChildren().addAll(backupLabel, spacer, quickBackupBtn);
        return statusBar;
    }

    private void exportDiagnostics() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Save Diagnostics ZIP");
        fileChooser.setInitialFileName("udhyamsetu_diagnostics_" +
                context.getClock().now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".zip");
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("ZIP Archive (*.zip)", "*.zip"));

        File targetFile = fileChooser.showSaveDialog(screenManager.getStage());
        if (targetFile != null) {
            try {
                context.getDiagnosticsService().exportDiagnostics(targetFile.toPath());
                Alert alert = new Alert(Alert.AlertType.INFORMATION, "Diagnostics ZIP exported successfully:\n" + targetFile.getAbsolutePath(), ButtonType.OK);
                alert.setTitle("Diagnostics Export");
                alert.showAndWait();
            } catch (Exception ex) {
                Alert alert = new Alert(Alert.AlertType.ERROR, "Failed to export diagnostics: " + ex.getMessage(), ButtonType.OK);
                alert.setTitle("Export Failed");
                alert.showAndWait();
            }
        }
    }
}
