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

        Button addSupplierBtn = new Button("+ Add Supplier");
        addSupplierBtn.setStyle("-fx-background-color: #f1f5f9; -fx-border-color: #cbd5e1; -fx-border-radius: 4; -fx-font-weight: bold;");
        addSupplierBtn.setOnAction(e -> openAddSupplierDialog());

        Button addProductBtn = new Button("+ Add Product");
        addProductBtn.setStyle("-fx-background-color: #f1f5f9; -fx-border-color: #cbd5e1; -fx-border-radius: 4; -fx-font-weight: bold;");
        addProductBtn.setOnAction(e -> openAddProductDialog());

        Button stockInBtn = new Button("+ Record Stock In (Distributor Purchase)");
        stockInBtn.setStyle("-fx-background-color: #059669; -fx-text-fill: white; -fx-font-weight: bold; -fx-background-radius: 4;");
        stockInBtn.setOnAction(e -> openStockInDialog());

        topBar.getChildren().addAll(backBtn, title, spacer, addSupplierBtn, addProductBtn, stockInBtn);
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
                    return context.getCustomerSupplierService().createSupplier(
                            name,
                            phoneField.getText().trim(),
                            contactPersonField.getText().trim(),
                            addressField.getText().trim(),
                            gstinField.getText().trim(),
                            notesField.getText().trim()
                    );
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
        dialog.setTitle("Add New Product & Variant");
        dialog.setHeaderText("Create Product in Electrical Catalogue");

        ButtonType saveBtnType = new ButtonType("Save Product", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveBtnType, ButtonType.CANCEL);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20));

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

        TextField productNameField = new TextField();
        productNameField.setPromptText("e.g. Fabio 1-Way Switch 10A");

        ComboBox<String> unitCombo = new ComboBox<>(FXCollections.observableArrayList("PIECE", "METRE", "COIL", "BOX"));
        unitCombo.setValue("PIECE");

        TextField variantNameField = new TextField("Standard");
        TextField barcodeField = new TextField();
        barcodeField.setPromptText("Optional barcode");

        TextField mrpField = new TextField("100.00");
        TextField sellingPriceField = new TextField("80.00");
        TextField reorderLevelField = new TextField("5");

        grid.add(new Label("Brand *:"), 0, 0);
        grid.add(brandCombo, 1, 0);
        grid.add(new Label("Category *:"), 0, 1);
        grid.add(categoryCombo, 1, 1);
        grid.add(new Label("Product Name *:"), 0, 2);
        grid.add(productNameField, 1, 2);
        grid.add(new Label("Unit *:"), 0, 3);
        grid.add(unitCombo, 1, 3);
        grid.add(new Label("Variant Name:"), 0, 4);
        grid.add(variantNameField, 1, 4);
        grid.add(new Label("Barcode:"), 0, 5);
        grid.add(barcodeField, 1, 5);
        grid.add(new Label("MRP (Rs) *:"), 0, 6);
        grid.add(mrpField, 1, 6);
        grid.add(new Label("Selling Price (Rs) *:"), 0, 7);
        grid.add(sellingPriceField, 1, 7);
        grid.add(new Label("Reorder Level:"), 0, 8);
        grid.add(reorderLevelField, 1, 8);

        dialog.getDialogPane().setContent(grid);

        dialog.setResultConverter(btn -> {
            if (btn == saveBtnType) {
                String brandName = brandCombo.getValue() != null ? brandCombo.getValue().trim() : "";
                String catName = categoryCombo.getValue() != null ? categoryCombo.getValue().trim() : "";
                String prodName = productNameField.getText().trim();
                String unit = unitCombo.getValue();
                String varName = variantNameField.getText().trim().isEmpty() ? "Standard" : variantNameField.getText().trim();
                String barcode = barcodeField.getText().trim().isEmpty() ? null : barcodeField.getText().trim();

                if (brandName.isEmpty() || catName.isEmpty() || prodName.isEmpty()) {
                    Alert alert = new Alert(Alert.AlertType.ERROR, "Brand, Category, and Product Name are required.", ButtonType.OK);
                    alert.showAndWait();
                    return false;
                }

                try {
                    // Find or create Brand
                    Brand brand = context.getBrandCategoryService().getAllBrands().stream()
                            .filter(b -> b.name().equalsIgnoreCase(brandName))
                            .findFirst()
                            .orElseGet(() -> context.getBrandCategoryService().createBrand(brandName));

                    // Find or create Category
                    Category category = context.getBrandCategoryService().getAllCategories().stream()
                            .filter(c -> c.name().equalsIgnoreCase(catName))
                            .findFirst()
                            .orElseGet(() -> context.getBrandCategoryService().createCategory(catName));

                    Quantity reorder = reorderLevelField.getText().trim().isEmpty()
                            ? null
                            : Quantity.of(new BigDecimal(reorderLevelField.getText().trim()));

                    Product product = context.getProductService().createProduct(brand.id(), category.id(), prodName, unit, reorder);

                    Money mrp = Money.parse(mrpField.getText().trim());
                    Money sp = Money.parse(sellingPriceField.getText().trim());

                    context.getProductService().createVariant(product.id(), varName, barcode, mrp, sp);

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

    private void openStockInDialog() {
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
        supplierCombo.setCellFactory(p -> new ListCell<>() {
            @Override
            protected void updateItem(Supplier s, boolean empty) {
                super.updateItem(s, empty);
                setText(empty || s == null ? null : s.name());
            }
        });
        supplierCombo.setButtonCell(supplierCombo.getCellFactory().call(null));
        supplierCombo.setValue(suppliers.get(0));

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
        variantCombo.setCellFactory(p -> new ListCell<>() {
            @Override
            protected void updateItem(StockRow r, boolean empty) {
                super.updateItem(r, empty);
                setText(empty || r == null ? null : r.productName() + " (" + r.variantName() + ") - " + r.brandName());
            }
        });
        variantCombo.setButtonCell(variantCombo.getCellFactory().call(null));
        if (!allRows.isEmpty()) variantCombo.setValue(allRows.get(0));

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
        paidAmountField.setPromptText("Leave empty for full payment");

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
