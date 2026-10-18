package com.shopbilling.ui;

import com.shopbilling.model.*;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * Screen managing inventory levels, low-stock indicators, and recording stock-in purchases
 * (PRD Section 7.4, FR-15 - FR-19).
 */
public class StockScreen {

    private final ScreenManager screenManager;
    private final AppContext context;

    private final ObservableList<StockRow> stockData = FXCollections.observableArrayList();
    private final TableView<StockRow> tableView = new TableView<>(stockData);
    private final TextField searchField = new TextField();

    public StockScreen(ScreenManager screenManager) {
        this.screenManager = Objects.requireNonNull(screenManager, "ScreenManager cannot be null");
        this.context = screenManager.getContext();
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

        Button stockInBtn = new Button("+ Record Stock In (Distributor Purchase)");
        stockInBtn.setStyle("-fx-background-color: #059669; -fx-text-fill: white; -fx-font-weight: bold; -fx-background-radius: 4;");
        stockInBtn.setOnAction(e -> openStockInDialog());

        topBar.getChildren().addAll(backBtn, title, spacer, stockInBtn);
        root.setTop(topBar);

        // Center: search + table
        VBox centerBox = new VBox(15);
        centerBox.setPadding(new Insets(15, 25, 15, 25));

        searchField.setPromptText("Search products, variants, or brands...");
        searchField.setPrefWidth(350);
        searchField.textProperty().addListener((obs, oldVal, newVal) -> filterStock(newVal));

        setupTable();
        VBox.setVgrow(tableView, Priority.ALWAYS);

        centerBox.getChildren().addAll(searchField, tableView);
        root.setCenter(centerBox);

        loadData();
        return root;
    }

    private void setupTable() {
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

        tableView.getColumns().addAll(colProduct, colVariant, colBrand, colUnit, colPrice, colStock, colReorder, colStatus);
    }

    private final List<StockRow> allRows = new ArrayList<>();

    private void loadData() {
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
                        p.name(),
                        v.variantName(),
                        brandName,
                        p.unitCode(),
                        v.sellingPrice(),
                        currentStock,
                        reorder,
                        status
                ));
            }
        }
        stockData.setAll(allRows);
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

    private void openStockInDialog() {
        List<Supplier> suppliers = context.getCustomerSupplierService().getAllSuppliers();
        if (suppliers.isEmpty()) {
            Alert alert = new Alert(Alert.AlertType.WARNING, "No suppliers found. Please add a supplier first.", ButtonType.OK);
            alert.showAndWait();
            return;
        }

        Dialog<Boolean> dialog = new Dialog<>();
        dialog.setTitle("Record Stock In (Distributor Purchase)");
        dialog.setHeaderText("Enter Distributor Invoice & Purchase Line Item");

        ButtonType confirmBtnType = new ButtonType("Save Stock In", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(confirmBtnType, ButtonType.CANCEL);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20, 150, 10, 10));

        ComboBox<Supplier> supplierCombo = new ComboBox<>(FXCollections.observableArrayList(suppliers));
        supplierCombo.setCellFactory(p -> new ListCell<>() {
            @Override
            protected void updateItem(Supplier s, boolean empty) {
                super.updateItem(s, empty);
                setText(empty || s == null ? null : s.name());
            }
        });
        supplierCombo.setButtonCell(supplierCombo.getCellFactory().call(null));
        supplierCombo.setValue(suppliers.get(0));

        TextField invoiceNumField = new TextField();
        invoiceNumField.setPromptText("e.g. INV-2026-999");

        ComboBox<StockRow> variantCombo = new ComboBox<>(FXCollections.observableArrayList(allRows));
        variantCombo.setCellFactory(p -> new ListCell<>() {
            @Override
            protected void updateItem(StockRow r, boolean empty) {
                super.updateItem(r, empty);
                setText(empty || r == null ? null : r.productName() + " (" + r.variantName() + ") - " + r.brandName());
            }
        });
        variantCombo.setButtonCell(variantCombo.getCellFactory().call(null));
        if (!allRows.isEmpty()) variantCombo.setValue(allRows.get(0));

        TextField qtyField = new TextField("10");
        TextField costPriceField = new TextField("50.00");
        TextField paidAmountField = new TextField();
        paidAmountField.setPromptText("Leave empty for full payment");

        grid.add(new Label("Distributor Supplier:"), 0, 0);
        grid.add(supplierCombo, 1, 0);
        grid.add(new Label("Invoice Number:"), 0, 1);
        grid.add(invoiceNumField, 1, 1);
        grid.add(new Label("Product Variant:"), 0, 2);
        grid.add(variantCombo, 1, 2);
        grid.add(new Label("Purchased Quantity:"), 0, 3);
        grid.add(qtyField, 1, 3);
        grid.add(new Label("Cost Price per unit (Rs):"), 0, 4);
        grid.add(costPriceField, 1, 4);
        grid.add(new Label("Amount Paid Now (Rs):"), 0, 5);
        grid.add(paidAmountField, 1, 5);

        dialog.getDialogPane().setContent(grid);

        dialog.setResultConverter(btn -> {
            if (btn == confirmBtnType) {
                try {
                    Supplier supp = supplierCombo.getValue();
                    String invNum = invoiceNumField.getText().trim();
                    StockRow row = variantCombo.getValue();
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
            String productName,
            String variantName,
            String brandName,
            String unitCode,
            Money sellingPrice,
            Quantity currentStock,
            Quantity reorderLevel,
            String status
    ) {}
}
