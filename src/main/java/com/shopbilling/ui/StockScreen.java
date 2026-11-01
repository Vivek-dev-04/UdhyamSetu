package com.shopbilling.ui;

import com.shopbilling.model.*;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.util.StringConverter;
import com.shopbilling.model.ClassificationStatus;
import com.shopbilling.model.ProductGroup;
import com.shopbilling.model.HsnCandidate;

import java.io.File;
import java.io.FileWriter;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Screen managing inventory levels, low-stock indicators, reorder advisory, and recording stock-in purchases
 * (PRD Section 7.4, FR-15 - FR-19).
 */
public class StockScreen {

    private final ScreenManager screenManager;
    private final AppContext context;
    private final int initialTabIndex;
    private final boolean openStockInImmediately;

    // Tab 1: All Stock
    private final ObservableList<StockRow> stockData = FXCollections.observableArrayList();
    private final TableView<StockRow> tableView = new TableView<>(stockData);
    private final TextField searchField = new TextField();
    private final List<StockRow> allRows = new ArrayList<>();

    // Tab 2: Low Stock Alerts
    private final ObservableList<LowStockItem> lowStockData = FXCollections.observableArrayList();
    private final TableView<LowStockItem> lowStockTable = new TableView<>(lowStockData);
    private final TextField lowStockSearchField = new TextField();
    private final List<LowStockItem> allLowStockItems = new ArrayList<>();
    private final Label lowStockBanner = new Label();

    // Tab 3: Purchases (Stock In) History
    private final ObservableList<PurchaseRow> purchaseData = FXCollections.observableArrayList();
    private final TableView<PurchaseRow> purchaseTable = new TableView<>(purchaseData);
    private final TextField purchaseSearchField = new TextField();
    private final List<Purchase> allPurchasesList = new ArrayList<>();
    private final List<PurchaseRow> allPurchaseRows = new ArrayList<>();

    private final ObservableList<PurchaseItemRow> purchaseItemsData = FXCollections.observableArrayList();
    private final TableView<PurchaseItemRow> purchaseItemsTable = new TableView<>(purchaseItemsData);
    private final Label purchaseDetailsLabel = new Label("Select a purchase above to inspect items");

    public StockScreen(ScreenManager screenManager) {
        this(screenManager, 0, false);
    }

    public StockScreen(ScreenManager screenManager, int initialTabIndex) {
        this(screenManager, initialTabIndex, false);
    }

    public StockScreen(ScreenManager screenManager, int initialTabIndex, boolean openStockInImmediately) {
        this.screenManager = Objects.requireNonNull(screenManager, "ScreenManager cannot be null");
        this.context = screenManager.getContext();
        this.initialTabIndex = initialTabIndex;
        this.openStockInImmediately = openStockInImmediately;
    }

    public Parent getView() {
        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color: #f8fafc;");

        // Top bar
        HBox topBar = new HBox(15);
        topBar.setAlignment(Pos.CENTER_LEFT);
        topBar.setPadding(new Insets(15, 25, 15, 25));
        topBar.setStyle("-fx-background-color: #ffffff; -fx-border-color: #e2e8f0; -fx-border-width: 0 0 1 0;");

        Button backBtn = new Button("← Back to Home");
        backBtn.setStyle("-fx-background-color: #f1f5f9; -fx-border-color: #cbd5e1; -fx-border-radius: 4;");
        backBtn.setOnAction(e -> screenManager.showHome());

        Label title = new Label("Inventory & Stock Management");
        title.setStyle("-fx-font-size: 20px; -fx-font-weight: bold; -fx-text-fill: #0f172a;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button hsnDirBtn = new Button("📑 HSN Directory");
        hsnDirBtn.setStyle("-fx-background-color: #0f766e; -fx-text-fill: white; -fx-font-weight: bold; -fx-background-radius: 4; -fx-cursor: hand;");
        hsnDirBtn.setOnAction(e -> screenManager.showHsnManagement());

        Button addSupplierBtn = new Button("+ Add Supplier");
        addSupplierBtn.setStyle("-fx-background-color: #f1f5f9; -fx-border-color: #cbd5e1; -fx-border-radius: 4; -fx-font-weight: bold; -fx-cursor: hand;");
        addSupplierBtn.setOnAction(e -> openAddSupplierDialog());

        Button addProductBtn = new Button("+ Add Product");
        addProductBtn.setStyle("-fx-background-color: #2563eb; -fx-text-fill: white; -fx-border-color: #1d4ed8; -fx-border-radius: 4; -fx-font-weight: bold; -fx-cursor: hand;");
        addProductBtn.setOnAction(e -> openAddProductDialog());

        Button stockInBtn = new Button("+ Record Stock In (Distributor Purchase)");
        stockInBtn.setStyle("-fx-background-color: #059669; -fx-text-fill: white; -fx-font-weight: bold; -fx-background-radius: 4; -fx-cursor: hand;");
        stockInBtn.setOnAction(e -> openStockInDialog());

        topBar.getChildren().addAll(backBtn, title, spacer, hsnDirBtn, addSupplierBtn, addProductBtn, stockInBtn);
        root.setTop(topBar);

        // Center tabs
        TabPane tabPane = new TabPane();
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        Tab stockTab = new Tab("All Stock Levels", buildStockTab());
        Tab lowStockTab = new Tab("Low-Stock Alerts & Reorder", buildLowStockTab());
        Tab stockInTab = new Tab("Record Stock In (Distributor Purchases)", buildStockInTab());

        tabPane.getTabs().addAll(stockTab, lowStockTab, stockInTab);

        if (initialTabIndex > 0 && initialTabIndex < tabPane.getTabs().size()) {
            tabPane.getSelectionModel().select(initialTabIndex);
        }

        root.setCenter(tabPane);

        loadData();

        if (openStockInImmediately) {
            Platform.runLater(this::openStockInDialog);
        }

        return root;
    }

    private VBox buildStockTab() {
        VBox box = new VBox(15);
        box.setPadding(new Insets(15, 25, 15, 25));

        HBox filterBar = new HBox(15);
        filterBar.setAlignment(Pos.CENTER_LEFT);

        searchField.setPromptText("Search products, variants, or brands...");
        searchField.setPrefWidth(350);
        searchField.textProperty().addListener((obs, oldVal, newVal) -> filterStock(newVal));

        Button refreshBtn = new Button("↻ Refresh");
        refreshBtn.setStyle("-fx-background-color: #f1f5f9; -fx-border-color: #cbd5e1; -fx-border-radius: 4;");
        refreshBtn.setOnAction(e -> loadData());

        filterBar.getChildren().addAll(searchField, refreshBtn);

        setupStockTable();
        VBox.setVgrow(tableView, Priority.ALWAYS);

        box.getChildren().addAll(filterBar, tableView);
        return box;
    }

    private VBox buildLowStockTab() {
        VBox box = new VBox(15);
        box.setPadding(new Insets(15, 25, 15, 25));

        lowStockBanner.setMaxWidth(Double.MAX_VALUE);

        HBox actionBar = new HBox(15);
        actionBar.setAlignment(Pos.CENTER_LEFT);

        lowStockSearchField.setPromptText("Filter low-stock items...");
        lowStockSearchField.setPrefWidth(300);
        lowStockSearchField.textProperty().addListener((obs, oldVal, newVal) -> filterLowStock(newVal));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button exportCsvBtn = new Button("📥 Export Reorder Sheet (CSV)");
        exportCsvBtn.setStyle("-fx-background-color: #f1f5f9; -fx-border-color: #cbd5e1; -fx-border-radius: 4; -fx-font-weight: bold;");
        exportCsvBtn.setOnAction(e -> exportLowStockCsv());

        Button orderSelectedBtn = new Button("+ Quick Stock In for Selected Item");
        orderSelectedBtn.setStyle("-fx-background-color: #059669; -fx-text-fill: white; -fx-font-weight: bold; -fx-background-radius: 4;");
        orderSelectedBtn.setOnAction(e -> {
            LowStockItem selected = lowStockTable.getSelectionModel().getSelectedItem();
            if (selected == null) {
                Alert alert = new Alert(Alert.AlertType.INFORMATION, "Please select an item from the table to record stock in, or use '+ Record Stock In' from the top bar.", ButtonType.OK);
                alert.showAndWait();
            } else {
                openStockInDialog(selected.variantId());
            }
        });

        actionBar.getChildren().addAll(lowStockSearchField, spacer, exportCsvBtn, orderSelectedBtn);

        setupLowStockTable();
        VBox.setVgrow(lowStockTable, Priority.ALWAYS);

        box.getChildren().addAll(lowStockBanner, actionBar, lowStockTable);
        return box;
    }

    private VBox buildStockInTab() {
        VBox box = new VBox(15);
        box.setPadding(new Insets(15, 25, 15, 25));

        HBox topAction = new HBox(15);
        topAction.setAlignment(Pos.CENTER_LEFT);

        Button recordBtn = new Button("+ Record Stock In (Distributor Purchase)");
        recordBtn.setStyle("-fx-background-color: #059669; -fx-text-fill: white; -fx-font-size: 13px; -fx-font-weight: bold; -fx-padding: 8 16; -fx-background-radius: 4;");
        recordBtn.setOnAction(e -> openStockInDialog());

        purchaseSearchField.setPromptText("Search purchases by invoice # or supplier...");
        purchaseSearchField.setPrefWidth(320);
        purchaseSearchField.textProperty().addListener((obs, oldVal, newVal) -> filterPurchases(newVal));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label helperLabel = new Label("Tracks distributor invoices, cost prices, payments, and credit payables (FR-16)");
        helperLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #64748b;");

        topAction.getChildren().addAll(recordBtn, purchaseSearchField, spacer, helperLabel);

        setupPurchasesTable();
        setupPurchaseItemsTable();

        VBox.setVgrow(purchaseTable, Priority.ALWAYS);

        purchaseDetailsLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: #1e293b; -fx-padding: 5 0 0 0;");
        purchaseItemsTable.setPrefHeight(180);

        box.getChildren().addAll(topAction, purchaseTable, purchaseDetailsLabel, purchaseItemsTable);
        return box;
    }

    private void setupStockTable() {
        TableColumn<StockRow, String> colProduct = new TableColumn<>("Product");
        colProduct.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().productName()));
        colProduct.setPrefWidth(220);

        TableColumn<StockRow, String> colVariant = new TableColumn<>("Variant");
        colVariant.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().variantName()));
        colVariant.setPrefWidth(140);

        TableColumn<StockRow, String> colBrand = new TableColumn<>("Brand");
        colBrand.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().brandName()));
        colBrand.setPrefWidth(120);

        TableColumn<StockRow, String> colUnit = new TableColumn<>("Unit");
        colUnit.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().unitCode()));
        colUnit.setPrefWidth(70);

        TableColumn<StockRow, String> colPrice = new TableColumn<>("Selling Price");
        colPrice.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().sellingPrice().toFormattedString()));
        colPrice.setPrefWidth(110);

        TableColumn<StockRow, String> colStock = new TableColumn<>("Current Stock");
        colStock.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().currentStock().toDisplayString() + " " + d.getValue().unitCode()));
        colStock.setPrefWidth(120);

        TableColumn<StockRow, String> colReorder = new TableColumn<>("Reorder Level");
        colReorder.setCellValueFactory(d -> new SimpleStringProperty(
                d.getValue().reorderLevel() != null ? d.getValue().reorderLevel().toDisplayString() : "-"
        ));
        colReorder.setPrefWidth(110);

        TableColumn<StockRow, String> colStatus = new TableColumn<>("Stock Status");
        colStatus.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().status()));
        colStatus.setPrefWidth(130);
        colStatus.setCellFactory(column -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setStyle("");
                } else {
                    setText(item);
                    if (item.contains("LOW STOCK") || item.contains("OUT OF STOCK")) {
                        setStyle("-fx-text-fill: #dc2626; -fx-font-weight: bold;");
                    } else {
                        setStyle("-fx-text-fill: #16a34a; -fx-font-weight: bold;");
                    }
                }
            }
        });

        TableColumn<StockRow, String> colHsn = new TableColumn<>("HSN Code");
        colHsn.setCellValueFactory(d -> new SimpleStringProperty(
                d.getValue().hsnCode() != null ? d.getValue().hsnCode() : "-"
        ));
        colHsn.setPrefWidth(95);
        colHsn.setStyle("-fx-alignment: CENTER;");

        TableColumn<StockRow, String> colHsnStatus = new TableColumn<>("HSN Status");
        colHsnStatus.setCellValueFactory(d -> new SimpleStringProperty(
                d.getValue().hsnStatus() != null ? d.getValue().hsnStatus().name() : "PENDING"
        ));
        colHsnStatus.setPrefWidth(110);
        colHsnStatus.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setStyle("");
                } else {
                    setText(item);
                    if ("VERIFIED".equals(item)) {
                        setStyle("-fx-text-fill: #16a34a; -fx-font-weight: bold; -fx-alignment: CENTER;");
                    } else if ("REJECTED".equals(item)) {
                        setStyle("-fx-text-fill: #dc2626; -fx-font-weight: bold; -fx-alignment: CENTER;");
                    } else if ("REVIEW_REQUIRED".equals(item)) {
                        setStyle("-fx-text-fill: #ea580c; -fx-font-weight: bold; -fx-alignment: CENTER;");
                    } else {
                        setStyle("-fx-text-fill: #4b5563; -fx-alignment: CENTER;");
                    }
                }
            }
        });

        TableColumn<StockRow, Void> colAction = new TableColumn<>("Actions");
        colAction.setPrefWidth(110);
        colAction.setStyle("-fx-alignment: CENTER;");
        colAction.setCellFactory(param -> new TableCell<>() {
            private final Button editBtn = new Button("Edit Product");
            {
                editBtn.setStyle("-fx-background-color: #eff6ff; -fx-text-fill: #2563eb; -fx-font-weight: bold; -fx-border-color: #bfdbfe; -fx-border-radius: 3; -fx-cursor: hand;");
                editBtn.setOnAction(e -> {
                    StockRow row = getTableView().getItems().get(getIndex());
                    if (row.productId() > 0) {
                        openEditProductDialog(row.productId());
                    }
                });
            }

            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) setGraphic(null);
                else setGraphic(editBtn);
            }
        });

        tableView.getColumns().addAll(colProduct, colVariant, colBrand, colUnit, colHsn, colHsnStatus, colPrice, colStock, colReorder, colStatus, colAction);
    }

    private void setupLowStockTable() {
        TableColumn<LowStockItem, String> colProduct = new TableColumn<>("Product");
        colProduct.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().productName()));
        colProduct.setPrefWidth(220);

        TableColumn<LowStockItem, String> colVariant = new TableColumn<>("Variant");
        colVariant.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().variantName()));
        colVariant.setPrefWidth(130);

        TableColumn<LowStockItem, String> colBrand = new TableColumn<>("Brand");
        colBrand.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().brandName()));
        colBrand.setPrefWidth(120);

        TableColumn<LowStockItem, String> colCategory = new TableColumn<>("Category");
        colCategory.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().categoryName()));
        colCategory.setPrefWidth(110);

        TableColumn<LowStockItem, String> colUnit = new TableColumn<>("Unit");
        colUnit.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().unitCode()));
        colUnit.setPrefWidth(70);

        TableColumn<LowStockItem, String> colStock = new TableColumn<>("Current Stock");
        colStock.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().currentStock().toDisplayString() + " " + d.getValue().unitCode()));
        colStock.setPrefWidth(110);
        colStock.setStyle("-fx-text-fill: #dc2626; -fx-font-weight: bold;");

        TableColumn<LowStockItem, String> colReorder = new TableColumn<>("Reorder Level");
        colReorder.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().reorderLevel().toDisplayString() + " " + d.getValue().unitCode()));
        colReorder.setPrefWidth(110);

        TableColumn<LowStockItem, String> colShortage = new TableColumn<>("Shortage");
        colShortage.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().shortage().toDisplayString()));
        colShortage.setPrefWidth(100);
        colShortage.setStyle("-fx-text-fill: #ea580c; -fx-font-weight: bold;");

        TableColumn<LowStockItem, String> colSuggested = new TableColumn<>("Suggested Order");
        colSuggested.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().suggestedOrderQuantity().toDisplayString() + " " + d.getValue().unitCode()));
        colSuggested.setPrefWidth(130);
        colSuggested.setStyle("-fx-text-fill: #16a34a; -fx-font-weight: bold;");

        lowStockTable.getColumns().addAll(colProduct, colVariant, colBrand, colCategory, colUnit, colStock, colReorder, colShortage, colSuggested);
    }

    private void setupPurchasesTable() {
        TableColumn<PurchaseRow, String> colId = new TableColumn<>("ID");
        colId.setCellValueFactory(d -> new SimpleStringProperty("#" + d.getValue().id()));
        colId.setPrefWidth(60);

        TableColumn<PurchaseRow, String> colDate = new TableColumn<>("Date");
        colDate.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().date().toString()));
        colDate.setPrefWidth(100);

        TableColumn<PurchaseRow, String> colSupplier = new TableColumn<>("Supplier (Distributor)");
        colSupplier.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().supplierName()));
        colSupplier.setPrefWidth(180);

        TableColumn<PurchaseRow, String> colInvoice = new TableColumn<>("Invoice #");
        colInvoice.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().invoiceNumber()));
        colInvoice.setPrefWidth(130);

        TableColumn<PurchaseRow, String> colTotal = new TableColumn<>("Total Amount");
        colTotal.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().totalAmount().toFormattedString()));
        colTotal.setPrefWidth(120);

        TableColumn<PurchaseRow, String> colPaid = new TableColumn<>("Paid");
        colPaid.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().paidAmount().toFormattedString()));
        colPaid.setPrefWidth(110);

        TableColumn<PurchaseRow, String> colCredit = new TableColumn<>("Credit / Due");
        colCredit.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().creditAmount().toFormattedString()));
        colCredit.setPrefWidth(120);
        colCredit.setCellFactory(column -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setStyle("");
                } else {
                    setText(item);
                    if (!item.contains("0.00") && !item.equals("₹0") && !item.equals("0")) {
                        setStyle("-fx-text-fill: #dc2626; -fx-font-weight: bold;");
                    } else {
                        setStyle("-fx-text-fill: #16a34a;");
                    }
                }
            }
        });

        TableColumn<PurchaseRow, String> colItems = new TableColumn<>("Items");
        colItems.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().itemCount() + " line(s)"));
        colItems.setPrefWidth(90);

        TableColumn<PurchaseRow, String> colNotes = new TableColumn<>("Notes");
        colNotes.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().notes()));
        colNotes.setPrefWidth(150);

        purchaseTable.getColumns().addAll(colId, colDate, colSupplier, colInvoice, colTotal, colPaid, colCredit, colItems, colNotes);

        purchaseTable.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) {
                showPurchaseItems(newVal.id(), newVal.supplierName(), newVal.invoiceNumber());
            } else {
                purchaseItemsData.clear();
                purchaseDetailsLabel.setText("Select a purchase above to inspect items");
            }
        });
    }

    private void setupPurchaseItemsTable() {
        TableColumn<PurchaseItemRow, String> colProd = new TableColumn<>("Product / Variant");
        colProd.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().productName() + " (" + d.getValue().variantName() + ")"));
        colProd.setPrefWidth(280);

        TableColumn<PurchaseItemRow, String> colQty = new TableColumn<>("Purchased Qty");
        colQty.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().quantity().toDisplayString()));
        colQty.setPrefWidth(120);

        TableColumn<PurchaseItemRow, String> colCost = new TableColumn<>("Cost Price per Unit");
        colCost.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().costPrice().toFormattedString()));
        colCost.setPrefWidth(150);

        TableColumn<PurchaseItemRow, String> colTotal = new TableColumn<>("Line Total");
        colTotal.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().lineTotal().toFormattedString()));
        colTotal.setPrefWidth(140);
        colTotal.setStyle("-fx-font-weight: bold;");

        purchaseItemsTable.getColumns().addAll(colProd, colQty, colCost, colTotal);
    }

    private void showPurchaseItems(long purchaseId, String supplierName, String invoiceNum) {
        purchaseItemsData.clear();
        Purchase match = allPurchasesList.stream().filter(p -> p.id() != null && p.id() == purchaseId).findFirst().orElse(null);
        if (match == null) return;

        purchaseDetailsLabel.setText("Items for Invoice " + invoiceNum + " (" + supplierName + "):");

        Map<Long, StockRow> variantMap = allRows.stream().collect(Collectors.toMap(StockRow::variantId, r -> r, (a, b) -> a));

        for (PurchaseItem item : match.items()) {
            StockRow row = variantMap.get(item.variantId());
            String pName = row != null ? row.productName() : "Product #" + item.variantId();
            String vName = row != null ? row.variantName() : "Variant #" + item.variantId();
            purchaseItemsData.add(new PurchaseItemRow(
                    pName,
                    vName,
                    item.quantity(),
                    item.costPrice(),
                    item.lineTotal()
            ));
        }
    }

    private void loadData() {
        loadStockData();
        loadLowStockData();
        loadPurchasesData();
    }

    private void loadStockData() {
        allRows.clear();
        List<ProductWithVariants> products = context.getProductService().searchProducts(null);
        for (ProductWithVariants pwv : products) {
            Product p = pwv.product();
            String brandName = pwv.brand() != null ? pwv.brand().name() : "";
            for (ProductVariant v : pwv.variants()) {
                Quantity currentStock = context.getStockService().getCurrentStock(v.id());
                Quantity reorder = p.reorderLevelScaled() != null ? Quantity.ofScaled(p.reorderLevelScaled()) : null;

                String status = "OK";
                if (currentStock.isZero()) {
                    status = "OUT OF STOCK";
                } else if (reorder != null && currentStock.compareTo(reorder) <= 0) {
                    status = "LOW STOCK";
                }

                allRows.add(new StockRow(
                        v.id(),
                        p.id(),
                        p.name(),
                        v.variantName(),
                        brandName,
                        p.unitCode(),
                        v.sellingPrice(),
                        currentStock,
                        reorder,
                        status,
                        p.hsnCode(),
                        p.hsnStatus() != null ? p.hsnStatus() : ClassificationStatus.PENDING
                ));
            }
        }
        stockData.setAll(allRows);
    }

    private void loadLowStockData() {
        allLowStockItems.clear();
        List<LowStockItem> items = context.getLowStockService().getLowStockItems();
        allLowStockItems.addAll(items);
        lowStockData.setAll(allLowStockItems);

        if (allLowStockItems.isEmpty()) {
            lowStockBanner.setText("  ✅ All inventory items are currently well-stocked above reorder thresholds.");
            lowStockBanner.setStyle("-fx-background-color: #f0fdf4; -fx-text-fill: #166534; -fx-padding: 10 14; -fx-background-radius: 6; -fx-border-color: #bbf7d0; -fx-border-radius: 6; -fx-font-weight: bold;");
        } else {
            lowStockBanner.setText("  ⚠️ " + allLowStockItems.size() + " item(s) are at or below reorder level. Review suggested replenishment order quantities below.");
            lowStockBanner.setStyle("-fx-background-color: #fef2f2; -fx-text-fill: #991b1b; -fx-padding: 10 14; -fx-background-radius: 6; -fx-border-color: #fecaca; -fx-border-radius: 6; -fx-font-weight: bold;");
        }
    }

    private void loadPurchasesData() {
        allPurchasesList.clear();
        allPurchaseRows.clear();
        Map<Long, String> supplierNameMap = context.getCustomerSupplierService().getAllSuppliers().stream()
                .collect(Collectors.toMap(Supplier::id, Supplier::name, (a, b) -> a));

        List<Purchase> purchases = context.getStockInService().getAllPurchases();
        allPurchasesList.addAll(purchases);

        for (Purchase p : purchases) {
            String suppName = supplierNameMap.getOrDefault(p.supplierId(), "Supplier #" + p.supplierId());
            allPurchaseRows.add(new PurchaseRow(
                    p.id(),
                    p.invoiceDate(),
                    suppName,
                    p.invoiceNumber(),
                    p.totalAmount(),
                    p.paidAmount(),
                    p.creditAmount(),
                    p.notes() != null ? p.notes() : "-",
                    p.items().size()
            ));
        }
        purchaseData.setAll(allPurchaseRows);
    }

    private void filterStock(String query) {
        if (query == null || query.trim().isEmpty()) {
            stockData.setAll(allRows);
            return;
        }
        String q = query.toLowerCase().trim();
        stockData.setAll(allRows.stream()
                .filter(r -> r.productName().toLowerCase().contains(q) ||
                        r.variantName().toLowerCase().contains(q) ||
                        r.brandName().toLowerCase().contains(q))
                .toList()
        );
    }

    private void filterLowStock(String query) {
        if (query == null || query.trim().isEmpty()) {
            lowStockData.setAll(allLowStockItems);
            return;
        }
        String q = query.toLowerCase().trim();
        lowStockData.setAll(allLowStockItems.stream()
                .filter(r -> r.productName().toLowerCase().contains(q) ||
                        r.variantName().toLowerCase().contains(q) ||
                        r.brandName().toLowerCase().contains(q) ||
                        r.categoryName().toLowerCase().contains(q))
                .toList()
        );
    }

    private void filterPurchases(String query) {
        if (query == null || query.trim().isEmpty()) {
            purchaseData.setAll(allPurchaseRows);
            return;
        }
        String q = query.toLowerCase().trim();
        purchaseData.setAll(allPurchaseRows.stream()
                .filter(r -> r.invoiceNumber().toLowerCase().contains(q) ||
                        r.supplierName().toLowerCase().contains(q) ||
                        r.notes().toLowerCase().contains(q))
                .toList()
        );
    }

    private void exportLowStockCsv() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Save Low-Stock Reorder Sheet CSV");
        fileChooser.setInitialFileName("low_stock_reorder_sheet.csv");
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV Files (*.csv)", "*.csv"));

        File file = fileChooser.showSaveDialog(screenManager.getStage());
        if (file != null) {
            try (FileWriter fw = new FileWriter(file)) {
                String csv = context.getLowStockService().exportLowStockCsv();
                fw.write(csv);
                Alert alert = new Alert(Alert.AlertType.INFORMATION, "Reorder sheet CSV exported successfully to:\n" + file.getAbsolutePath(), ButtonType.OK);
                alert.showAndWait();
            } catch (Exception ex) {
                Alert alert = new Alert(Alert.AlertType.ERROR, "CSV export failed: " + ex.getMessage(), ButtonType.OK);
                alert.showAndWait();
            }
        }
    }

    public Supplier openAddSupplierDialog() {
        Dialog<Supplier> dialog = new Dialog<>();
        dialog.setTitle("Add New Supplier (Distributor)");
        dialog.setHeaderText("Register Distributor / Wholesale Supplier");

        ButtonType saveBtnType = new ButtonType("Save Supplier", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveBtnType, ButtonType.CANCEL);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20));

        TextField nameField = new TextField();
        nameField.setPromptText("e.g. Havells Agencies / Sharma Electricals");
        TextField phoneField = new TextField();
        phoneField.setPromptText("e.g. 9876543210");
        TextField contactPersonField = new TextField();
        contactPersonField.setPromptText("e.g. Rajesh Kumar");
        TextField addressField = new TextField();
        addressField.setPromptText("e.g. MI Road, Jaipur");
        TextField gstinField = new TextField();
        gstinField.setPromptText("15-digit GSTIN (optional)");
        TextField notesField = new TextField();
        notesField.setPromptText("Optional notes");

        grid.add(new Label("Supplier Name *:"), 0, 0);
        grid.add(nameField, 1, 0);
        grid.add(new Label("Phone:"), 0, 1);
        grid.add(phoneField, 1, 1);
        grid.add(new Label("Contact Person:"), 0, 2);
        grid.add(contactPersonField, 1, 2);
        grid.add(new Label("Address:"), 0, 3);
        grid.add(addressField, 1, 3);
        grid.add(new Label("GSTIN:"), 0, 4);
        grid.add(gstinField, 1, 4);
        grid.add(new Label("Notes:"), 0, 5);
        grid.add(notesField, 1, 5);

        dialog.getDialogPane().setContent(grid);

        dialog.setResultConverter(btn -> {
            if (btn == saveBtnType) {
                String name = nameField.getText().trim();
                if (name.isEmpty()) {
                    Alert alert = new Alert(Alert.AlertType.ERROR, "Supplier name cannot be empty.", ButtonType.OK);
                    alert.showAndWait();
                    return null;
                }
                try {
                    Supplier created = context.getCustomerSupplierService().createSupplier(
                            name,
                            phoneField.getText().trim(),
                            contactPersonField.getText().trim(),
                            addressField.getText().trim(),
                            gstinField.getText().trim(),
                            notesField.getText().trim()
                    );
                    loadData();
                    return created;
                } catch (Exception ex) {
                    Alert alert = new Alert(Alert.AlertType.ERROR, "Failed to create supplier: " + ex.getMessage(), ButtonType.OK);
                    alert.showAndWait();
                    return null;
                }
            }
            return null;
        });

        return dialog.showAndWait().orElse(null);
    }

    public boolean openAddProductDialog() {
        Dialog<Boolean> dialog = new Dialog<>();
        dialog.setTitle("Add New Product & Statutory HSN Classification");
        dialog.setHeaderText("Create Product in Electrical/Sanitary Catalogue");

        ButtonType saveBtnType = new ButtonType("Save Product", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveBtnType, ButtonType.CANCEL);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(15));

        // Brands
        List<Brand> brands = context.getBrandCategoryService().getAllBrands();
        ComboBox<String> brandCombo = new ComboBox<>();
        brandCombo.setEditable(true);
        for (Brand b : brands) {
            brandCombo.getItems().add(b.name());
        }
        if (!brandCombo.getItems().isEmpty()) {
            brandCombo.setValue(brandCombo.getItems().get(0));
        } else {
            brandCombo.setValue("Havells");
        }

        // Categories
        List<Category> categories = context.getBrandCategoryService().getAllCategories();
        ComboBox<String> categoryCombo = new ComboBox<>();
        categoryCombo.setEditable(true);
        for (Category c : categories) {
            categoryCombo.getItems().add(c.name());
        }
        if (!categoryCombo.getItems().isEmpty()) {
            categoryCombo.setValue(categoryCombo.getItems().get(0));
        } else {
            categoryCombo.setValue("Switch");
        }

        // Product Groups (Tally Hierarchy)
        List<ProductGroup> groups = context.getProductGroupService().getAllGroups(false);
        ComboBox<ProductGroup> groupCombo = new ComboBox<>();
        groupCombo.setConverter(new StringConverter<>() {
            @Override public String toString(ProductGroup g) {
                return g != null ? g.groupName() + (g.defaultHsnCode() != null ? " (" + g.defaultHsnCode() + ")" : "") : "-- No Group / Standalone --";
            }
            @Override public ProductGroup fromString(String s) { return null; }
        });
        groupCombo.getItems().add(null);
        groupCombo.getItems().addAll(groups);
        groupCombo.setValue(null);

        TextField productNameField = new TextField();
        productNameField.setPromptText("e.g. Fabio 1-Way Switch 10A");

        // HSN Search Component
        HsnSearchComponent hsnSearch = new HsnSearchComponent(context.getHsnService());

        ComboBox<Double> gstCombo = new ComboBox<>(FXCollections.observableArrayList(0.0, 5.0, 12.0, 18.0, 28.0));
        gstCombo.setValue(18.0);

        ComboBox<ClassificationStatus> statusCombo = new ComboBox<>(FXCollections.observableArrayList(ClassificationStatus.values()));
        statusCombo.setValue(ClassificationStatus.PENDING);

        // Auto-Inherit from Group when group selected
        groupCombo.setOnAction(e -> {
            ProductGroup g = groupCombo.getValue();
            if (g != null) {
                if (g.defaultHsnCode() != null && !g.defaultHsnCode().isBlank()) {
                    hsnSearch.selectByCode(g.defaultHsnCode());
                }
                if (g.defaultGstRate() != null) {
                    gstCombo.setValue(g.defaultGstRate());
                }
            }
        });

        // Classification Engine suggestions box
        VBox suggestionsBox = new VBox(6);
        suggestionsBox.setStyle("-fx-background-color: #f8fafc; -fx-padding: 8; -fx-border-color: #e2e8f0; -fx-border-radius: 4;");
        Label suggestionsHeader = new Label("GRI Classification Candidates:");
        suggestionsHeader.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: #475569;");
        VBox candidateList = new VBox(4);
        suggestionsBox.getChildren().addAll(suggestionsHeader, candidateList);
        suggestionsBox.setVisible(false);
        suggestionsBox.setManaged(false);

        Button classifyBtn = new Button("⚡ Auto-Classify (GRI Engine)");
        classifyBtn.setStyle("-fx-background-color: #e0e7ff; -fx-text-fill: #3730a3; -fx-font-weight: bold; -fx-cursor: hand;");
        classifyBtn.setOnAction(e -> {
            String name = productNameField.getText().trim();
            String brand = brandCombo.getValue();
            String cat = categoryCombo.getValue();
            List<HsnCandidate> candidates = context.getHsnClassificationEngine().classify(name, brand, cat);
            candidateList.getChildren().clear();
            if (candidates.isEmpty()) {
                candidateList.getChildren().add(new Label("No specific heading rule matched. Use directory search."));
            } else {
                for (HsnCandidate cand : candidates) {
                    HBox card = new HBox(8);
                    card.setAlignment(Pos.CENTER_LEFT);
                    card.setStyle("-fx-background-color: white; -fx-padding: 6; -fx-border-color: #cbd5e1; -fx-border-radius: 4;");

                    VBox info = new VBox(2);
                    Label codeLbl = new Label("[" + cand.getPriorityLabel() + "] HSN " + cand.hsnCode() + " - " + cand.officialDescription());
                    codeLbl.setStyle("-fx-font-weight: bold; -fx-font-size: 12px;");
                    Label basisLbl = new Label("Basis: " + cand.matchReason());
                    basisLbl.setStyle("-fx-font-size: 11px; -fx-text-fill: #64748b;");
                    info.getChildren().addAll(codeLbl, basisLbl);
                    if ((cand.missingSpecifications() != null && !cand.missingSpecifications().isBlank())) {
                        Label missLbl = new Label("⚠️ " + cand.missingSpecifications());
                        missLbl.setStyle("-fx-font-size: 11px; -fx-text-fill: #b45309;");
                        info.getChildren().add(missLbl);
                    }

                    Region sp = new Region();
                    HBox.setHgrow(sp, Priority.ALWAYS);

                    Button selectBtn = new Button("Apply");
                    selectBtn.setStyle("-fx-background-color: #0284c7; -fx-text-fill: white; -fx-font-weight: bold;");
                    selectBtn.setOnAction(ev -> {
                        hsnSearch.selectByCode(cand.hsnCode());
                        if (cand.suggestedGstRate() != null) {
                            gstCombo.setValue(cand.suggestedGstRate());
                        }
                        statusCombo.setValue(cand.status() != null ? cand.status() : ClassificationStatus.SUGGESTED);
                    });

                    card.getChildren().addAll(info, sp, selectBtn);
                    candidateList.getChildren().add(card);
                }
            }
            suggestionsBox.setVisible(true);
            suggestionsBox.setManaged(true);
        });

        HBox nameRow = new HBox(8, productNameField, classifyBtn);
        HBox.setHgrow(productNameField, Priority.ALWAYS);

        Button quickVerifyBtn = new Button("✓ Mark Verified");
        quickVerifyBtn.setStyle("-fx-background-color: #dcfce7; -fx-text-fill: #166534; -fx-font-weight: bold;");
        quickVerifyBtn.setOnAction(e -> statusCombo.setValue(ClassificationStatus.VERIFIED));

        HBox statusRow = new HBox(8, statusCombo, quickVerifyBtn);

        ComboBox<String> unitCombo = new ComboBox<>(FXCollections.observableArrayList("PIECE", "METRE", "COIL", "BOX"));
        unitCombo.setValue("PIECE");

        TextField variantNameField = new TextField("Standard");
        TextField mrpField = new TextField("100.00");
        TextField sellingPriceField = new TextField("80.00");
        TextField reorderLevelField = new TextField("5");

        int r = 0;
        grid.add(new Label("Brand *:"), 0, r); grid.add(brandCombo, 1, r++);
        grid.add(new Label("Category *:"), 0, r); grid.add(categoryCombo, 1, r++);
        grid.add(new Label("Product Group:"), 0, r); grid.add(groupCombo, 1, r++);
        grid.add(new Label("Product Name *:"), 0, r); grid.add(nameRow, 1, r++);
        grid.add(new Label("Suggestions:"), 0, r); grid.add(suggestionsBox, 1, r++);
        grid.add(new Label("HSN/SAC Code:"), 0, r); grid.add(hsnSearch, 1, r++);
        grid.add(new Label("Classification Status:"), 0, r); grid.add(statusRow, 1, r++);
        grid.add(new Label("GST Rate (%):"), 0, r); grid.add(gstCombo, 1, r++);
        grid.add(new Label("Unit *:"), 0, r); grid.add(unitCombo, 1, r++);
        grid.add(new Label("Variant Name:"), 0, r); grid.add(variantNameField, 1, r++);
        grid.add(new Label("MRP (Rs) *:"), 0, r); grid.add(mrpField, 1, r++);
        grid.add(new Label("Selling Price (Rs) *:"), 0, r); grid.add(sellingPriceField, 1, r++);
        grid.add(new Label("Reorder Level:"), 0, r); grid.add(reorderLevelField, 1, r++);

        dialog.getDialogPane().setContent(grid);

        dialog.setResultConverter(btn -> {
            if (btn == saveBtnType) {
                String brandName = brandCombo.getValue() != null ? brandCombo.getValue().trim() : "";
                String catName = categoryCombo.getValue() != null ? categoryCombo.getValue().trim() : "";
                String prodName = productNameField.getText().trim();
                String unit = unitCombo.getValue();
                String varName = variantNameField.getText().trim().isEmpty() ? "Standard" : variantNameField.getText().trim();

                if (brandName.isEmpty() || catName.isEmpty() || prodName.isEmpty()) {
                    Alert alert = new Alert(Alert.AlertType.ERROR, "Brand, Category, and Product Name are required.", ButtonType.OK);
                    alert.showAndWait();
                    return false;
                }

                try {
                    Brand brand = context.getBrandCategoryService().getAllBrands().stream()
                            .filter(b -> b.name().equalsIgnoreCase(brandName))
                            .findFirst()
                            .orElseGet(() -> context.getBrandCategoryService().createBrand(brandName));

                    Category category = context.getBrandCategoryService().getAllCategories().stream()
                            .filter(c -> c.name().equalsIgnoreCase(catName))
                            .findFirst()
                            .orElseGet(() -> context.getBrandCategoryService().createCategory(catName));

                    Quantity reorder = reorderLevelField.getText().trim().isEmpty()
                            ? null
                            : Quantity.of(new BigDecimal(reorderLevelField.getText().trim()));

                    String selectedHsn = hsnSearch.getSelectedHsnCode();
                    Double gstRate = gstCombo.getValue() != null ? gstCombo.getValue() : 18.0;
                    Long groupId = groupCombo.getValue() != null ? groupCombo.getValue().id() : null;
                    ClassificationStatus hsnStatus = statusCombo.getValue();

                    Product product = context.getProductService().createProduct(
                            brand.id(),
                            category.id(),
                            prodName,
                            unit,
                            reorder,
                            selectedHsn,
                            gstRate,
                            groupId,
                            hsnStatus
                    );

                    if (selectedHsn != null && !selectedHsn.isBlank() && hsnStatus == ClassificationStatus.VERIFIED) {
                        try {
                            context.getHsnVerificationService().verifyAndApprove(
                                    product.id(),
                                    selectedHsn,
                                    gstRate,
                                    "ADMIN",
                                    "Verified during product creation"
                            );
                        } catch (Exception ignore) {}
                    }

                    Money mrp = Money.parse(mrpField.getText().trim());
                    Money sp = Money.parse(sellingPriceField.getText().trim());

                    context.getProductService().addVariant(product.id(), varName, mrp, sp, null);

                    loadData();
                    return true;
                } catch (Exception ex) {
                    Alert alert = new Alert(Alert.AlertType.ERROR, "Failed to create product: " + ex.getMessage(), ButtonType.OK);
                    alert.showAndWait();
                    return false;
                }
            }
            return false;
        });

        return dialog.showAndWait().orElse(false);
    }

    public boolean openEditProductDialog(long productId) {
        Optional<ProductWithVariants> pwvOpt = context.getProductService().getProductWithVariants(productId);
        if (pwvOpt.isEmpty()) {
            Alert alert = new Alert(Alert.AlertType.ERROR, "Product id=" + productId + " not found.", ButtonType.OK);
            alert.showAndWait();
            return false;
        }

        ProductWithVariants pwv = pwvOpt.get();
        Product product = pwv.product();

        Dialog<Boolean> dialog = new Dialog<>();
        dialog.setTitle("Edit Product & Statutory HSN Classification");
        dialog.setHeaderText("Update Product: " + product.name());

        ButtonType saveBtnType = new ButtonType("Save Changes", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveBtnType, ButtonType.CANCEL);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(15));

        // Brands
        List<Brand> brands = context.getBrandCategoryService().getAllBrands();
        ComboBox<String> brandCombo = new ComboBox<>();
        brandCombo.setEditable(true);
        for (Brand b : brands) {
            brandCombo.getItems().add(b.name());
        }
        if (pwv.brand() != null) {
            brandCombo.setValue(pwv.brand().name());
        }

        // Categories
        List<Category> categories = context.getBrandCategoryService().getAllCategories();
        ComboBox<String> categoryCombo = new ComboBox<>();
        categoryCombo.setEditable(true);
        for (Category c : categories) {
            categoryCombo.getItems().add(c.name());
        }
        if (pwv.category() != null) {
            categoryCombo.setValue(pwv.category().name());
        }

        // Product Groups (Tally Hierarchy)
        List<ProductGroup> groups = context.getProductGroupService().getAllGroups(false);
        ComboBox<ProductGroup> groupCombo = new ComboBox<>();
        groupCombo.setConverter(new StringConverter<>() {
            @Override public String toString(ProductGroup g) {
                return g != null ? g.groupName() + (g.defaultHsnCode() != null ? " (" + g.defaultHsnCode() + ")" : "") : "-- No Group / Standalone --";
            }
            @Override public ProductGroup fromString(String s) { return null; }
        });
        groupCombo.getItems().add(null);
        groupCombo.getItems().addAll(groups);
        if (product.groupId() != null) {
            for (ProductGroup g : groups) {
                if (g.id().equals(product.groupId())) {
                    groupCombo.setValue(g);
                    break;
                }
            }
        } else {
            groupCombo.setValue(null);
        }

        TextField productNameField = new TextField(product.name());

        // HSN Search Component pre-populated
        HsnSearchComponent hsnSearch = new HsnSearchComponent(context.getHsnService());
        if (product.hsnCode() != null && !product.hsnCode().isEmpty()) {
            hsnSearch.selectByCode(product.hsnCode());
        }

        ComboBox<Double> gstCombo = new ComboBox<>(FXCollections.observableArrayList(0.0, 5.0, 12.0, 18.0, 28.0));
        gstCombo.setValue(product.gstRate() != null ? product.gstRate() : 18.0);

        ComboBox<ClassificationStatus> statusCombo = new ComboBox<>(FXCollections.observableArrayList(ClassificationStatus.values()));
        statusCombo.setValue(product.hsnStatus() != null ? product.hsnStatus() : ClassificationStatus.PENDING);

        // Auto-Inherit from Group when group selected
        groupCombo.setOnAction(e -> {
            ProductGroup g = groupCombo.getValue();
            if (g != null) {
                if (g.defaultHsnCode() != null && !g.defaultHsnCode().isBlank()) {
                    hsnSearch.selectByCode(g.defaultHsnCode());
                }
                if (g.defaultGstRate() != null) {
                    gstCombo.setValue(g.defaultGstRate());
                }
            }
        });

        // Classification Engine suggestions box
        VBox suggestionsBox = new VBox(6);
        suggestionsBox.setStyle("-fx-background-color: #f8fafc; -fx-padding: 8; -fx-border-color: #e2e8f0; -fx-border-radius: 4;");
        Label suggestionsHeader = new Label("GRI Classification Candidates:");
        suggestionsHeader.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: #475569;");
        VBox candidateList = new VBox(4);
        suggestionsBox.getChildren().addAll(suggestionsHeader, candidateList);
        suggestionsBox.setVisible(false);
        suggestionsBox.setManaged(false);

        Button classifyBtn = new Button("⚡ Auto-Classify (GRI Engine)");
        classifyBtn.setStyle("-fx-background-color: #e0e7ff; -fx-text-fill: #3730a3; -fx-font-weight: bold; -fx-cursor: hand;");
        classifyBtn.setOnAction(e -> {
            String name = productNameField.getText().trim();
            String brand = brandCombo.getValue();
            String cat = categoryCombo.getValue();
            List<HsnCandidate> candidates = context.getHsnClassificationEngine().classify(name, brand, cat);
            candidateList.getChildren().clear();
            if (candidates.isEmpty()) {
                candidateList.getChildren().add(new Label("No specific heading rule matched. Use directory search."));
            } else {
                for (HsnCandidate cand : candidates) {
                    HBox card = new HBox(8);
                    card.setAlignment(Pos.CENTER_LEFT);
                    card.setStyle("-fx-background-color: white; -fx-padding: 6; -fx-border-color: #cbd5e1; -fx-border-radius: 4;");

                    VBox info = new VBox(2);
                    Label codeLbl = new Label("[" + cand.getPriorityLabel() + "] HSN " + cand.hsnCode() + " - " + cand.officialDescription());
                    codeLbl.setStyle("-fx-font-weight: bold; -fx-font-size: 12px;");
                    Label basisLbl = new Label("Basis: " + cand.matchReason());
                    basisLbl.setStyle("-fx-font-size: 11px; -fx-text-fill: #64748b;");
                    info.getChildren().addAll(codeLbl, basisLbl);
                    if ((cand.missingSpecifications() != null && !cand.missingSpecifications().isBlank())) {
                        Label missLbl = new Label("⚠️ " + cand.missingSpecifications());
                        missLbl.setStyle("-fx-font-size: 11px; -fx-text-fill: #b45309;");
                        info.getChildren().add(missLbl);
                    }

                    Region sp = new Region();
                    HBox.setHgrow(sp, Priority.ALWAYS);

                    Button selectBtn = new Button("Apply");
                    selectBtn.setStyle("-fx-background-color: #0284c7; -fx-text-fill: white; -fx-font-weight: bold;");
                    selectBtn.setOnAction(ev -> {
                        hsnSearch.selectByCode(cand.hsnCode());
                        if (cand.suggestedGstRate() != null) {
                            gstCombo.setValue(cand.suggestedGstRate());
                        }
                        statusCombo.setValue(cand.status() != null ? cand.status() : ClassificationStatus.SUGGESTED);
                    });

                    card.getChildren().addAll(info, sp, selectBtn);
                    candidateList.getChildren().add(card);
                }
            }
            suggestionsBox.setVisible(true);
            suggestionsBox.setManaged(true);
        });

        HBox nameRow = new HBox(8, productNameField, classifyBtn);
        HBox.setHgrow(productNameField, Priority.ALWAYS);

        Button quickVerifyBtn = new Button("✓ Mark Verified");
        quickVerifyBtn.setStyle("-fx-background-color: #dcfce7; -fx-text-fill: #166534; -fx-font-weight: bold;");
        quickVerifyBtn.setOnAction(e -> statusCombo.setValue(ClassificationStatus.VERIFIED));

        HBox statusRow = new HBox(8, statusCombo, quickVerifyBtn);

        ComboBox<String> unitCombo = new ComboBox<>(FXCollections.observableArrayList("PIECE", "METRE", "COIL", "BOX"));
        unitCombo.setValue(product.unitCode());

        String reorderStr = product.reorderLevelScaled() != null
                ? Quantity.ofScaled(product.reorderLevelScaled()).toDisplayString()
                : "";
        TextField reorderLevelField = new TextField(reorderStr);

        int r = 0;
        grid.add(new Label("Brand *:"), 0, r); grid.add(brandCombo, 1, r++);
        grid.add(new Label("Category *:"), 0, r); grid.add(categoryCombo, 1, r++);
        grid.add(new Label("Product Group:"), 0, r); grid.add(groupCombo, 1, r++);
        grid.add(new Label("Product Name *:"), 0, r); grid.add(nameRow, 1, r++);
        grid.add(new Label("Suggestions:"), 0, r); grid.add(suggestionsBox, 1, r++);
        grid.add(new Label("HSN/SAC Code:"), 0, r); grid.add(hsnSearch, 1, r++);
        grid.add(new Label("Classification Status:"), 0, r); grid.add(statusRow, 1, r++);
        grid.add(new Label("GST Rate (%):"), 0, r); grid.add(gstCombo, 1, r++);
        grid.add(new Label("Unit *:"), 0, r); grid.add(unitCombo, 1, r++);
        grid.add(new Label("Reorder Level:"), 0, r); grid.add(reorderLevelField, 1, r++);

        dialog.getDialogPane().setContent(grid);

        dialog.setResultConverter(btn -> {
            if (btn == saveBtnType) {
                String brandName = brandCombo.getValue() != null ? brandCombo.getValue().trim() : "";
                String catName = categoryCombo.getValue() != null ? categoryCombo.getValue().trim() : "";
                String prodName = productNameField.getText().trim();
                String unit = unitCombo.getValue();

                if (brandName.isEmpty() || catName.isEmpty() || prodName.isEmpty()) {
                    Alert alert = new Alert(Alert.AlertType.ERROR, "Brand, Category, and Product Name are required.", ButtonType.OK);
                    alert.showAndWait();
                    return false;
                }

                try {
                    Brand brand = context.getBrandCategoryService().getAllBrands().stream()
                            .filter(b -> b.name().equalsIgnoreCase(brandName))
                            .findFirst()
                            .orElseGet(() -> context.getBrandCategoryService().createBrand(brandName));

                    Category category = context.getBrandCategoryService().getAllCategories().stream()
                            .filter(c -> c.name().equalsIgnoreCase(catName))
                            .findFirst()
                            .orElseGet(() -> context.getBrandCategoryService().createCategory(catName));

                    Quantity reorder = reorderLevelField.getText().trim().isEmpty()
                            ? null
                            : Quantity.of(new BigDecimal(reorderLevelField.getText().trim()));

                    String newHsn = hsnSearch.getSelectedHsnCode();
                    Double newGst = gstCombo.getValue() != null ? gstCombo.getValue() : 18.0;
                    Long groupId = groupCombo.getValue() != null ? groupCombo.getValue().id() : null;
                    ClassificationStatus newStatus = statusCombo.getValue();

                    context.getProductService().updateProduct(
                            product.id(),
                            brand.id(),
                            category.id(),
                            prodName,
                            unit,
                            reorder,
                            product.isActive(),
                            newHsn,
                            newGst,
                            groupId,
                            newStatus
                    );

                    if (newHsn != null && !newHsn.isBlank() && newStatus == ClassificationStatus.VERIFIED) {
                        try {
                            context.getHsnVerificationService().verifyAndApprove(
                                    product.id(),
                                    newHsn,
                                    newGst,
                                    "ADMIN",
                                    "Verified in product catalogue edit dialog"
                            );
                        } catch (Exception ignore) {}
                    }

                    loadData();
                    return true;
                } catch (Exception ex) {
                    Alert alert = new Alert(Alert.AlertType.ERROR, "Failed to update product: " + ex.getMessage(), ButtonType.OK);
                    alert.showAndWait();
                    return false;
                }
            }
            return false;
        });

        return dialog.showAndWait().orElse(false);
    }

    public void openStockInDialog() {
        openStockInDialog(null);
    }

    public void openStockInDialog(Long preselectedVariantId) {
        List<Supplier> suppliers = context.getCustomerSupplierService().getAllSuppliers();
        if (suppliers.isEmpty()) {
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION, "No suppliers found in system. Would you like to add a distributor supplier now?", ButtonType.YES, ButtonType.NO);
            Optional<ButtonType> res = alert.showAndWait();
            if (res.isPresent() && res.get() == ButtonType.YES) {
                Supplier s = openAddSupplierDialog();
                if (s != null) {
                    suppliers = context.getCustomerSupplierService().getAllSuppliers();
                } else {
                    return;
                }
            } else {
                return;
            }
        }

        if (allRows.isEmpty()) {
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION, "No products found in catalogue. Would you like to add a product now?", ButtonType.YES, ButtonType.NO);
            Optional<ButtonType> res = alert.showAndWait();
            if (res.isPresent() && res.get() == ButtonType.YES) {
                boolean added = openAddProductDialog();
                if (!added || allRows.isEmpty()) {
                    return;
                }
            } else {
                return;
            }
        }

        Dialog<Boolean> dialog = new Dialog<>();
        dialog.setTitle("Record Stock In (Distributor Purchase)");
        dialog.setHeaderText("Enter Distributor Invoice & Purchase Line Item");

        ButtonType confirmBtnType = new ButtonType("Save Stock In", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(confirmBtnType, ButtonType.CANCEL);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20, 50, 10, 10));

        ComboBox<Supplier> supplierCombo = new ComboBox<>(FXCollections.observableArrayList(suppliers));
        supplierCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(Supplier s) {
                return s == null ? "" : s.name();
            }
            @Override
            public Supplier fromString(String string) {
                return null;
            }
        });
        supplierCombo.setCellFactory(p -> new ListCell<>() {
            @Override
            protected void updateItem(Supplier s, boolean empty) {
                super.updateItem(s, empty);
                setText(empty || s == null ? null : s.name());
            }
        });
        if (!suppliers.isEmpty()) {
            supplierCombo.setValue(suppliers.get(0));
        }

        Button quickAddSuppBtn = new Button("+");
        quickAddSuppBtn.setTooltip(new Tooltip("Add New Supplier"));
        quickAddSuppBtn.setOnAction(e -> {
            Supplier created = openAddSupplierDialog();
            if (created != null) {
                List<Supplier> updated = context.getCustomerSupplierService().getAllSuppliers();
                supplierCombo.setItems(FXCollections.observableArrayList(updated));
                supplierCombo.setValue(created);
            }
        });

        HBox supplierBox = new HBox(5, supplierCombo, quickAddSuppBtn);

        TextField invoiceNumField = new TextField();
        invoiceNumField.setPromptText("e.g. INV-2026-999");

        ComboBox<StockRow> variantCombo = new ComboBox<>(FXCollections.observableArrayList(allRows));
        variantCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(StockRow r) {
                return r == null ? "" : r.productName() + " (" + r.variantName() + ") - " + r.brandName();
            }
            @Override
            public StockRow fromString(String string) {
                return null;
            }
        });
        variantCombo.setCellFactory(p -> new ListCell<>() {
            @Override
            protected void updateItem(StockRow r, boolean empty) {
                super.updateItem(r, empty);
                setText(empty || r == null ? null : r.productName() + " (" + r.variantName() + ") - " + r.brandName());
            }
        });

        if (preselectedVariantId != null) {
            for (StockRow r : allRows) {
                if (r.variantId() == preselectedVariantId) {
                    variantCombo.setValue(r);
                    break;
                }
            }
        }
        if (variantCombo.getValue() == null && !allRows.isEmpty()) {
            variantCombo.setValue(allRows.get(0));
        }

        Button quickAddProdBtn = new Button("+");
        quickAddProdBtn.setTooltip(new Tooltip("Add New Product"));
        quickAddProdBtn.setOnAction(e -> {
            boolean created = openAddProductDialog();
            if (created) {
                variantCombo.setItems(FXCollections.observableArrayList(allRows));
                if (!allRows.isEmpty()) {
                    variantCombo.setValue(allRows.get(allRows.size() - 1));
                }
            }
        });

        HBox variantBox = new HBox(5, variantCombo, quickAddProdBtn);

        TextField qtyField = new TextField("10");
        TextField costPriceField = new TextField("50.00");
        TextField paidAmountField = new TextField();
        paidAmountField.setPromptText("Enter 0 for full credit, or amount paid upfront");

        Label creditHint = new Label("• Enter 0 if purchased on full credit (adds to Supplier Payables)\n• Enter partial amount if partially paid\n• Leave blank if paid in full immediately");
        creditHint.setStyle("-fx-font-size: 11px; -fx-text-fill: #64748b;");

        grid.add(new Label("Distributor Supplier:"), 0, 0);
        grid.add(supplierBox, 1, 0);
        grid.add(new Label("Invoice Number:"), 0, 1);
        grid.add(invoiceNumField, 1, 1);
        grid.add(new Label("Product Variant:"), 0, 2);
        grid.add(variantBox, 1, 2);
        grid.add(new Label("Purchased Quantity:"), 0, 3);
        grid.add(qtyField, 1, 3);
        grid.add(new Label("Cost Price per unit (Rs):"), 0, 4);
        grid.add(costPriceField, 1, 4);
        grid.add(new Label("Amount Paid Now (Rs):"), 0, 5);
        grid.add(paidAmountField, 1, 5);
        grid.add(creditHint, 1, 6);

        dialog.getDialogPane().setContent(grid);

        dialog.setResultConverter(btn -> {
            if (btn == confirmBtnType) {
                try {
                    Supplier supp = supplierCombo.getValue();
                    if (supp == null) {
                        Alert alert = new Alert(Alert.AlertType.ERROR, "Please select a supplier.", ButtonType.OK);
                        alert.showAndWait();
                        return false;
                    }
                    String invNum = invoiceNumField.getText().trim();
                    if (invNum.isEmpty()) {
                        Alert alert = new Alert(Alert.AlertType.ERROR, "Invoice number is required.", ButtonType.OK);
                        alert.showAndWait();
                        return false;
                    }
                    StockRow row = variantCombo.getValue();
                    if (row == null) {
                        Alert alert = new Alert(Alert.AlertType.ERROR, "Please select a product variant.", ButtonType.OK);
                        alert.showAndWait();
                        return false;
                    }
                    Quantity qty = Quantity.of(new BigDecimal(qtyField.getText().trim()));
                    Money costPrice = Money.parse(costPriceField.getText().trim());

                    Money totalCost = costPrice.times(qty.toBigDecimal(), java.math.RoundingMode.HALF_UP);
                    Money paid = paidAmountField.getText().trim().isEmpty() ? totalCost : Money.parse(paidAmountField.getText().trim());

                    long userId = context.getCurrentUser() != null ? context.getCurrentUser().id() : 1L;

                    context.getStockInService().recordStockIn(
                            supp.id(),
                            invNum,
                            context.getClock().today(),
                            List.of(new PurchaseItemInput(row.variantId(), qty, costPrice)),
                            paid,
                            "Stock-in entry",
                            userId
                    );

                    Alert alert = new Alert(Alert.AlertType.INFORMATION, "Stock in recorded successfully!\nStock balance updated.", ButtonType.OK);
                    alert.showAndWait();
                    loadData();
                    return true;
                } catch (Exception ex) {
                    Alert alert = new Alert(Alert.AlertType.ERROR, "Failed to record stock in: " + ex.getMessage(), ButtonType.OK);
                    alert.showAndWait();
                    return false;
                }
            }
            return null;
        });

        dialog.showAndWait();
    }

    public record StockRow(
            long variantId,
            long productId,
            String productName,
            String variantName,
            String brandName,
            String unitCode,
            Money sellingPrice,
            Quantity currentStock,
            Quantity reorderLevel,
            String status,
            String hsnCode,
            ClassificationStatus hsnStatus
    ) {
        public StockRow(long variantId, long productId, String productName, String variantName, String brandName, String unitCode, Money sellingPrice, Quantity currentStock, Quantity reorderLevel, String status, String hsnCode) {
            this(variantId, productId, productName, variantName, brandName, unitCode, sellingPrice, currentStock, reorderLevel, status, hsnCode, ClassificationStatus.PENDING);
        }
        public StockRow(long variantId, String productName, String variantName, String brandName, String unitCode, Money sellingPrice, Quantity currentStock, Quantity reorderLevel, String status) {
            this(variantId, 0L, productName, variantName, brandName, unitCode, sellingPrice, currentStock, reorderLevel, status, null, ClassificationStatus.PENDING);
        }
    }

    public record PurchaseRow(
            long id,
            LocalDate date,
            String supplierName,
            String invoiceNumber,
            Money totalAmount,
            Money paidAmount,
            Money creditAmount,
            String notes,
            int itemCount
    ) {}

    public record PurchaseItemRow(
            String productName,
            String variantName,
            Quantity quantity,
            Money costPrice,
            Money lineTotal
    ) {}
}
