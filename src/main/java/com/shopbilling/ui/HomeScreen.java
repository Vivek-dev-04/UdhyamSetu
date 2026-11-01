package com.shopbilling.ui;

import com.shopbilling.model.*;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;

import java.io.File;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.Optional;

/**
 * POS Touch Grid Home Screen.
 * Button-based interface consolidating all shop operations.
 * Low-stock alerts and distributor stock-in purchases are unified under 'Stock & Inventory'.
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

        // 1. TOP BAR: Modern Header (Branding + Financial Year + User Role + Logout)
        HBox headerBar = buildHeaderBar();
        root.setTop(headerBar);

        // 2. CENTER: POS Touch Action Grid (10 Balanced Tiles)
        GridPane grid = buildActionGrid();

        ScrollPane scrollPane = new ScrollPane(grid);
        scrollPane.setFitToWidth(true);
        scrollPane.setFitToHeight(true);
        scrollPane.setStyle("-fx-background-color: transparent; -fx-background: #f8fafc; -fx-border-color: transparent;");
        root.setCenter(scrollPane);

        // 3. BOTTOM: System Health, Last Backup Status & Quick Actions
        HBox statusBar = buildStatusBar();
        root.setBottom(statusBar);

        // Keyboard shortcuts mapping for fast counter operations
        root.setOnKeyPressed(event -> {
            KeyCode code = event.getCode();
            switch (code) {
                case F1 -> screenManager.showBilling(SaleMode.BILL);
                case F2 -> screenManager.showBilling(SaleMode.REGISTERED);
                case F3 -> screenManager.showSales();
                case F4 -> screenManager.showStock(0);
                case F5 -> screenManager.showCustomerDues();
                case F6 -> screenManager.showSupplierPayables();
                case F7 -> screenManager.showExpenses();
                case F8 -> handleDayEndClosingClick();
                case F9 -> screenManager.showReportsAndProfit();
                case F10 -> screenManager.showSettingsAndAdmin();
                case F11 -> screenManager.showHsnManagement();
                default -> {}
            }
        });

        return root;
    }

    private HBox buildHeaderBar() {
        HBox header = new HBox(15);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(12, 25, 12, 25));
        header.setStyle("-fx-background-color: #ffffff; -fx-border-color: #e2e8f0; -fx-border-width: 0 0 1 0;");

        Label brandTitle = new Label("UdhyamSetu");
        brandTitle.setStyle("-fx-font-size: 20px; -fx-font-weight: bold; -fx-text-fill: #0f172a;");

        Label subtitle = new Label("Electrical Goods • Touch POS Counter");
        subtitle.setStyle("-fx-font-size: 12px; -fx-text-fill: #64748b;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        FinancialYear fy = FinancialYear.ofDate(context.getClock().today());
        Quarter q = fy.getQuarterOf(context.getClock().today());
        Label fyBadge = new Label(fy.getLabel() + " • " + q.name());
        fyBadge.setStyle("-fx-background-color: #e0f2fe; -fx-text-fill: #0369a1; " +
                "-fx-padding: 5 12; -fx-background-radius: 6; -fx-font-size: 12px; -fx-font-weight: bold;");

        User user = context.getCurrentUser();
        String roleStr = user != null ? user.role().name() : "GUEST";
        String nameStr = user != null ? user.fullName() : "User";

        Label userBadge = new Label(nameStr + " (" + roleStr + ")");
        userBadge.setStyle("-fx-background-color: #f1f5f9; -fx-text-fill: #334155; " +
                "-fx-padding: 5 12; -fx-background-radius: 6; -fx-font-size: 12px; -fx-font-weight: bold;");

        Button logoutBtn = new Button("Logout");
        logoutBtn.setStyle("-fx-background-color: #fee2e2; -fx-text-fill: #dc2626; -fx-font-weight: bold; -fx-font-size: 12px; -fx-background-radius: 6; -fx-padding: 5 14; -fx-cursor: hand;");
        logoutBtn.setOnAction(e -> {
            context.setCurrentUser(null);
            screenManager.showLogin();
        });

        header.getChildren().addAll(brandTitle, subtitle, spacer, fyBadge, userBadge, logoutBtn);
        return header;
    }

    private GridPane buildActionGrid() {
        GridPane grid = new GridPane();
        grid.setAlignment(Pos.CENTER);
        grid.setHgap(18);
        grid.setVgap(18);
        grid.setPadding(new Insets(25, 30, 25, 30));

        // ROW 0: Counter Billing & Inventory (4 Columns)
        Button generateBillBtn = createTileButton(
                "🧾",
                "Generate Bill",
                "Official A4 / Thermal bill with tax invoice\n(Deducts stock, adds to turnover)",
                "#1e40af",
                "#2563eb",
                "F1",
                235,
                160
        );
        generateBillBtn.setOnAction(e -> screenManager.showBilling(SaleMode.BILL));

        Button registerSaleBtn = createTileButton(
                "⚡",
                "Register Sale",
                "Fast counter sale without printed invoice\n(Deducts stock, adds to turnover)",
                "#065f46",
                "#059669",
                "F2",
                235,
                160
        );
        registerSaleBtn.setOnAction(e -> screenManager.showBilling(SaleMode.REGISTERED));

        Button salesBtn = createTileButton(
                "↩️",
                "Sales & Returns",
                "Sales history, filters, convert sales,\ncredit notes, and customer returns",
                "#1e293b",
                "#334155",
                "F3",
                235,
                160
        );
        salesBtn.setOnAction(e -> screenManager.showSales());

        // Unified Inventory Tile (contains All Stock, Low Stock Alerts, and Record Stock In)
        Button inventoryBtn = createTileButton(
                "📦",
                "Stock & Inventory",
                "Stock levels, low-stock shortage alerts,\nand distributor stock-in purchase entry",
                "#b45309",
                "#d97706",
                "F4",
                235,
                160
        );
        inventoryBtn.setOnAction(e -> screenManager.showStock(0));

        grid.add(generateBillBtn, 0, 0);
        grid.add(registerSaleBtn, 1, 0);
        grid.add(salesBtn, 2, 0);
        grid.add(inventoryBtn, 3, 0);

        // ROW 1: Credit Ledgers & Daily Cash (4 Columns)
        Button customerDuesBtn = createTileButton(
                "👥",
                "Customer Dues",
                "Customer credit ledgers, receivables,\nand overdue aging (0-90+ days)",
                "#4338ca",
                "#4f46e5",
                "F5",
                235,
                160
        );
        customerDuesBtn.setOnAction(e -> screenManager.showCustomerDues());

        Button supplierPayablesBtn = createTileButton(
                "🏭",
                "Supplier Payables",
                "Distributor payables, credit balances,\nand purchase payment history",
                "#7e22ce",
                "#9333ea",
                "F6",
                235,
                160
        );
        supplierPayablesBtn.setOnAction(e -> screenManager.showSupplierPayables());

        Button expenseBtn = createTileButton(
                "💸",
                "Daily Expenses",
                "Record counter expenses: tea, snacks,\nfreight, utilities, and wages",
                "#c2410c",
                "#ea580c",
                "F7",
                235,
                160
        );
        expenseBtn.setOnAction(e -> screenManager.showExpenses());

        Button closingBtn = createTileButton(
                "🔒",
                "Day-End Closing",
                "Owner cash drawer reconciliation,\ndaily turnover, and auto-backup",
                "#0f5132",
                "#198754",
                "F8",
                235,
                160
        );
        closingBtn.setOnAction(e -> handleDayEndClosingClick());

        grid.add(customerDuesBtn, 0, 1);
        grid.add(supplierPayablesBtn, 1, 1);
        grid.add(expenseBtn, 2, 1);
        grid.add(closingBtn, 3, 1);

        // ROW 2: Executive Reports, HSN Directory & System Admin
        Button reportsBtn = createTileButton(
                "📊",
                "Reports & Profit Analysis",
                "Comprehensive sales reports, product margin analysis, CSV export,\nand Owner Weighted Average COGS profitability view",
                "#0369a1",
                "#0284c7",
                "F9",
                488,
                150
        );
        reportsBtn.setOnAction(e -> screenManager.showReportsAndProfit());

        Button hsnDirBtn = createTileButton(
                "📑",
                "HSN Directory",
                "Official GST tariff classifications,\nExcel importer, and search",
                "#0f766e",
                "#0d9488",
                "F11",
                235,
                150
        );
        hsnDirBtn.setOnAction(e -> screenManager.showHsnManagement());

        Button settingsBtn = createTileButton(
                "⚙️",
                "Settings & Admin",
                "Shop profile, GST credentials,\nthermal printer, and backup",
                "#475569",
                "#64748b",
                "F10",
                235,
                150
        );
        settingsBtn.setOnAction(e -> screenManager.showSettingsAndAdmin());

        grid.add(reportsBtn, 0, 2, 2, 1);
        grid.add(hsnDirBtn, 2, 2);
        grid.add(settingsBtn, 3, 2);

        return grid;
    }

    private void handleDayEndClosingClick() {
        if (!context.isOwner()) {
            Alert alert = new Alert(Alert.AlertType.WARNING, "Day-End Cash Closing is restricted to the Shop Owner.", ButtonType.OK);
            alert.setTitle("Owner Authorization Required");
            alert.showAndWait();
        } else {
            screenManager.showDayEndClosing();
        }
    }

    private Button createTileButton(String icon, String title, String description, String bgColor, String hoverColor, String shortcutKey, double prefWidth, double prefHeight) {
        Button btn = new Button();
        btn.setPrefSize(prefWidth, prefHeight);
        btn.setMinSize(220, 140);
        btn.setMaxWidth(Double.MAX_VALUE);

        btn.setStyle(String.format(
                "-fx-background-color: %s; " +
                "-fx-background-radius: 14px; " +
                "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.15), 14, 0, 0, 4); " +
                "-fx-cursor: hand; -fx-padding: 0;",
                bgColor
        ));

        VBox content = new VBox(7);
        content.setAlignment(Pos.CENTER);
        content.setPadding(new Insets(16, 20, 16, 20));

        HBox topRow = new HBox(8);
        topRow.setAlignment(Pos.CENTER);

        Label iconLabel = new Label(icon);
        iconLabel.setStyle("-fx-font-size: 18px;");

        Label shortcutBadge = new Label(shortcutKey);
        shortcutBadge.setStyle(
                "-fx-background-color: rgba(255,255,255,0.22); " +
                "-fx-text-fill: white; " +
                "-fx-font-weight: bold; " +
                "-fx-font-size: 11px; " +
                "-fx-padding: 2 7; " +
                "-fx-background-radius: 4px;"
        );

        topRow.getChildren().addAll(iconLabel, shortcutBadge);

        Label titleLabel = new Label(title);
        titleLabel.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: white;");
        titleLabel.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);

        Label descLabel = new Label(description);
        descLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: rgba(255,255,255,0.85); -fx-alignment: center;");
        descLabel.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        descLabel.setWrapText(true);

        content.getChildren().addAll(topRow, titleLabel, descLabel);
        btn.setGraphic(content);

        btn.setOnMouseEntered(e -> btn.setStyle(String.format(
                "-fx-background-color: %s; " +
                "-fx-background-radius: 14px; " +
                "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.28), 20, 0, 0, 7); " +
                "-fx-cursor: hand; -fx-padding: 0;",
                hoverColor
        )));

        btn.setOnMouseExited(e -> btn.setStyle(String.format(
                "-fx-background-color: %s; " +
                "-fx-background-radius: 14px; " +
                "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.15), 14, 0, 0, 4); " +
                "-fx-cursor: hand; -fx-padding: 0;",
                bgColor
        )));

        return btn;
    }

    private HBox buildStatusBar() {
        HBox statusBar = new HBox(15);
        statusBar.setAlignment(Pos.CENTER_LEFT);
        statusBar.setPadding(new Insets(10, 25, 10, 25));
        statusBar.setStyle("-fx-background-color: #ffffff; -fx-border-color: #e2e8f0; -fx-border-width: 1 0 0 0;");

        boolean isOlderThan24h = context.getBackupService().isBackupOlderThan24Hours();
        Optional<BackupRecord> lastBackup = context.getBackupService().getLastSuccessfulBackup();

        Label backupLabel = new Label();
        if (isOlderThan24h) {
            String timeStr = lastBackup.map(BackupRecord::createdAt).orElse("None recorded");
            backupLabel.setText("⚠️ Last Backup: " + timeStr + " (Older than 24h - Backup Recommended!)");
            backupLabel.setStyle("-fx-text-fill: #dc2626; -fx-font-weight: bold; -fx-font-size: 12px;");
        } else {
            String timeStr = lastBackup.map(BackupRecord::createdAt).orElse("");
            backupLabel.setText("✓ Last Backup: " + timeStr + " (Database Healthy)");
            backupLabel.setStyle("-fx-text-fill: #16a34a; -fx-font-weight: bold; -fx-font-size: 12px;");
        }

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button quickBackupBtn = new Button("Backup Now");
        quickBackupBtn.setStyle("-fx-background-color: #f1f5f9; -fx-border-color: #cbd5e1; -fx-border-radius: 4; -fx-font-size: 11px; -fx-cursor: hand;");
        quickBackupBtn.setOnAction(e -> {
            try {
                BackupRecord b = context.getBackupService().performBackup(BackupType.MANUAL, null);
                Alert alert = new Alert(Alert.AlertType.INFORMATION, "Backup created successfully at:\n" + b.backupFilePath(), ButtonType.OK);
                alert.setTitle("Backup Successful");
                alert.showAndWait();
                screenManager.showHome();
            } catch (Exception ex) {
                Alert alert = new Alert(Alert.AlertType.ERROR, "Backup failed: " + ex.getMessage(), ButtonType.OK);
                alert.setTitle("Backup Failed");
                alert.showAndWait();
            }
        });

        Button diagnosticsBtn = new Button("Export Diagnostics ZIP");
        diagnosticsBtn.setStyle("-fx-background-color: #f1f5f9; -fx-border-color: #cbd5e1; -fx-border-radius: 4; -fx-font-size: 11px; -fx-cursor: hand;");
        diagnosticsBtn.setOnAction(e -> exportDiagnostics());

        statusBar.getChildren().addAll(backupLabel, spacer, quickBackupBtn, diagnosticsBtn);
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
