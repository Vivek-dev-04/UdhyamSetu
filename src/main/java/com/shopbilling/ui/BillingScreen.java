package com.shopbilling.ui;

import com.shopbilling.model.*;
import com.shopbilling.service.pdf.BillPdfService;
import com.shopbilling.service.pdf.BillPrintModel;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;

import java.io.File;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;

/**
 * Keyboard-first billing and counter sales screen (PRD Section 7.3, FR-8 - FR-14).
 * Supports decimal quantities, per-line discounts, customer types,
 * split payments, and PDF bill generation.
 */
public class BillingScreen {

    private final ScreenManager screenManager;
    private final AppContext context;
    private final SaleMode saleMode;

    private final ObservableList<CartLineItem> cartItems = FXCollections.observableArrayList();
    private final ComboBox<Customer> customerComboBox = new ComboBox<>();
    private final ComboBox<ProductVariantWithInfo> productComboBox = new ComboBox<>();
    private final TextField quantityField = new TextField("1");
    private final TextField priceField = new TextField();
    private final TextField discountField = new TextField("0");
    private final TextField overallDiscountField = new TextField("0");

    private final Label subtotalLabel = new Label("₹0.00");
    private final Label totalDiscountLabel = new Label("₹0.00");
    private final Label grandTotalLabel = new Label("₹0.00");
    private final Label errorLabel = new Label();

    public BillingScreen(ScreenManager screenManager, SaleMode saleMode) {
        this.screenManager = Objects.requireNonNull(screenManager, "ScreenManager cannot be null");
        this.context = screenManager.getContext();
        this.saleMode = Objects.requireNonNull(saleMode, "SaleMode cannot be null");
    }

    public Parent getView() {
        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color: #f8fafc;");

        // 1. TOP BAR: Title, Mode Indicator, Customer Selector & Back Button
        VBox topBox = new VBox(10);
        topBox.setPadding(new Insets(15, 20, 10, 20));
        topBox.setStyle("-fx-background-color: #ffffff; -fx-border-color: #e2e8f0; -fx-border-width: 0 0 1 0;");

        HBox titleRow = new HBox(15);
        titleRow.setAlignment(Pos.CENTER_LEFT);

        Button backBtn = new Button("← Back to Home");
        backBtn.setStyle("-fx-background-color: #f1f5f9; -fx-border-color: #cbd5e1; -fx-border-radius: 4;");
        backBtn.setOnAction(e -> screenManager.showHome());

        Label titleLabel = new Label(saleMode == SaleMode.BILL ? "Generate Official Bill" : "Register Counter Sale");
        titleLabel.setStyle("-fx-font-size: 20px; -fx-font-weight: bold; -fx-text-fill: #0f172a;");

        Label modeBadge = new Label(saleMode == SaleMode.BILL ? "OFFICIAL BILL (A4 PDF)" : "UNBILLED SALE (STOCK DEDUCTED)");
        modeBadge.setStyle(saleMode == SaleMode.BILL
                ? "-fx-background-color: #dbeafe; -fx-text-fill: #1d4ed8; -fx-font-weight: bold; -fx-padding: 3 8; -fx-background-radius: 4;"
                : "-fx-background-color: #dcfce7; -fx-text-fill: #15803d; -fx-font-weight: bold; -fx-padding: 3 8; -fx-background-radius: 4;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        titleRow.getChildren().addAll(backBtn, titleLabel, modeBadge, spacer);

        // Customer selection row (FR-10)
        HBox customerRow = new HBox(15);
        customerRow.setAlignment(Pos.CENTER_LEFT);

        Label custLabel = new Label("Customer:");
        custLabel.setStyle("-fx-font-weight: bold;");

        loadCustomers();
        customerComboBox.setPrefWidth(300);
        customerComboBox.setPromptText("Select Customer (Default: Walk-in)");

        Button newCustBtn = new Button("+ New Customer");
        newCustBtn.setStyle("-fx-background-color: #f8fafc; -fx-border-color: #cbd5e1; -fx-border-radius: 4;");
        newCustBtn.setOnAction(e -> showAddCustomerDialog());

        customerRow.getChildren().addAll(custLabel, customerComboBox, newCustBtn);

        errorLabel.setStyle("-fx-text-fill: #dc2626; -fx-font-weight: bold;");
        errorLabel.setVisible(false);

        topBox.getChildren().addAll(titleRow, customerRow, errorLabel);
        root.setTop(topBox);

        // 2. CENTER: Product Entry Bar & Cart Table (FR-8, FR-9)
        VBox centerBox = new VBox(15);
        centerBox.setPadding(new Insets(15, 20, 15, 20));

        // Fast Entry Grid
        GridPane entryGrid = new GridPane();
        entryGrid.setHgap(10);
        entryGrid.setVgap(5);
        entryGrid.setPadding(new Insets(10));
        entryGrid.setStyle("-fx-background-color: #ffffff; -fx-background-radius: 6; -fx-border-color: #e2e8f0; -fx-border-radius: 6;");

        loadProducts();
        productComboBox.setPrefWidth(380);
        productComboBox.setPromptText("Search / Select Product");
        productComboBox.setOnAction(e -> {
            ProductVariantWithInfo selected = productComboBox.getValue();
            if (selected != null) {
                priceField.setText(selected.variant().sellingPrice().toRupeesString());
                quantityField.requestFocus();
            }
        });

        quantityField.setPrefWidth(80);
        quantityField.setPromptText("Qty");
        quantityField.setOnAction(e -> addItemToCart());

        priceField.setPrefWidth(100);
        priceField.setPromptText("Rate (Rs)");
        priceField.setOnAction(e -> addItemToCart());

        discountField.setPrefWidth(90);
        discountField.setPromptText("Disc (Rs)");
        discountField.setOnAction(e -> addItemToCart());

        Button addItemBtn = new Button("Add Item [Enter]");
        addItemBtn.setStyle("-fx-background-color: #0284c7; -fx-text-fill: white; -fx-font-weight: bold; -fx-background-radius: 4;");
        addItemBtn.setOnAction(e -> addItemToCart());

        entryGrid.add(new Label("Product & Variant:"), 0, 0);
        entryGrid.add(productComboBox, 0, 1);
        entryGrid.add(new Label("Quantity:"), 1, 0);
        entryGrid.add(quantityField, 1, 1);
        entryGrid.add(new Label("Rate (Rs):"), 2, 0);
        entryGrid.add(priceField, 2, 1);
        entryGrid.add(new Label("Discount (Rs):"), 3, 0);
        entryGrid.add(discountField, 3, 1);
        entryGrid.add(new Label("Action:"), 4, 0);
        entryGrid.add(addItemBtn, 4, 1);

        // Cart TableView
        TableView<CartLineItem> tableView = new TableView<>(cartItems);
        VBox.setVgrow(tableView, Priority.ALWAYS);

        TableColumn<CartLineItem, String> colSno = new TableColumn<>("#");
        colSno.setCellValueFactory(data -> new SimpleStringProperty(String.valueOf(cartItems.indexOf(data.getValue()) + 1)));
        colSno.setPrefWidth(40);

        TableColumn<CartLineItem, String> colItem = new TableColumn<>("Product & Variant");
        colItem.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().displayName()));
        colItem.setPrefWidth(340);

        TableColumn<CartLineItem, String> colQty = new TableColumn<>("Quantity");
        colQty.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().quantity().toDisplayString() + " " + data.getValue().unitCode()));
        colQty.setPrefWidth(110);

        TableColumn<CartLineItem, String> colRate = new TableColumn<>("Rate (Rs)");
        colRate.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().unitPrice().toRupeesString()));
        colRate.setPrefWidth(110);

        TableColumn<CartLineItem, String> colDisc = new TableColumn<>("Discount (Rs)");
        colDisc.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().lineDiscount().toRupeesString()));
        colDisc.setPrefWidth(110);

        TableColumn<CartLineItem, String> colTotal = new TableColumn<>("Line Total (Rs)");
        colTotal.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().lineTotal().toRupeesString()));
        colTotal.setPrefWidth(130);

        TableColumn<CartLineItem, Void> colAction = new TableColumn<>("Remove");
        colAction.setPrefWidth(80);
        colAction.setCellFactory(param -> new TableCell<>() {
            private final Button btn = new Button("✕");
            {
                btn.setStyle("-fx-background-color: transparent; -fx-text-fill: #dc2626; -fx-font-weight: bold;");
                btn.setOnAction(e -> {
                    CartLineItem item = getTableView().getItems().get(getIndex());
                    cartItems.remove(item);
                    updateTotals();
                });
            }
            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                setGraphic(empty ? null : btn);
            }
        });

        tableView.getColumns().addAll(colSno, colItem, colQty, colRate, colDisc, colTotal, colAction);
        centerBox.getChildren().addAll(entryGrid, tableView);
        root.setCenter(centerBox);

        // 3. BOTTOM: Totals Summary & Payment / Complete Sale (FR-11, FR-14)
        HBox bottomBox = new HBox(20);
        bottomBox.setAlignment(Pos.CENTER_RIGHT);
        bottomBox.setPadding(new Insets(15, 25, 15, 25));
        bottomBox.setStyle("-fx-background-color: #ffffff; -fx-border-color: #e2e8f0; -fx-border-width: 1 0 0 0;");

        GridPane totalsGrid = new GridPane();
        totalsGrid.setHgap(15);
        totalsGrid.setVgap(5);
        totalsGrid.setAlignment(Pos.CENTER_RIGHT);

        totalsGrid.add(new Label("Subtotal:"), 0, 0);
        totalsGrid.add(subtotalLabel, 1, 0);
        totalsGrid.add(new Label("Bill Discount:"), 0, 1);
        overallDiscountField.setPrefWidth(80);
        overallDiscountField.setOnAction(e -> updateTotals());
        totalsGrid.add(overallDiscountField, 1, 1);
        totalsGrid.add(new Label("Net Payable:"), 0, 2);
        grandTotalLabel.setStyle("-fx-font-size: 22px; -fx-font-weight: bold; -fx-text-fill: #059669;");
        totalsGrid.add(grandTotalLabel, 1, 2);

        Button completeBtn = new Button("Complete Sale [F12]");
        completeBtn.setPrefSize(200, 60);
        completeBtn.setStyle("-fx-background-color: #16a34a; -fx-text-fill: white; -fx-font-size: 16px; " +
                "-fx-font-weight: bold; -fx-background-radius: 8px; -fx-cursor: hand;");
        completeBtn.setOnAction(e -> openPaymentDialog());

        bottomBox.getChildren().addAll(totalsGrid, completeBtn);
        root.setBottom(bottomBox);

        // F12 shortcut
        root.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.F12) {
                openPaymentDialog();
            }
        });

        return root;
    }

    private void addItemToCart() {
        errorLabel.setVisible(false);
        ProductVariantWithInfo selected = productComboBox.getValue();
        if (selected == null) {
            errorLabel.setText("Please select a product");
            errorLabel.setVisible(true);
            return;
        }

        try {
            BigDecimal qtyVal = new BigDecimal(quantityField.getText().trim());
            Quantity qty = Quantity.of(qtyVal);
            Money rate = Money.parse(priceField.getText().trim());
            Money disc = discountField.getText().trim().isEmpty() ? Money.ZERO : Money.parse(discountField.getText().trim());

            Money lineSubtotal = rate.times(qty.toBigDecimal(), java.math.RoundingMode.HALF_UP);
            Money lineTotal = lineSubtotal.minus(disc);

            cartItems.add(new CartLineItem(
                    selected.variant().id(),
                    selected.product().name() + " (" + selected.variant().variantName() + ") - " + selected.brandName(),
                    selected.unitCode(),
                    qty,
                    rate,
                    disc,
                    lineTotal
            ));

            // Reset inputs
            quantityField.setText("1");
            discountField.setText("0");
            productComboBox.setValue(null);
            priceField.clear();
            updateTotals();
            productComboBox.requestFocus();

        } catch (Exception ex) {
            errorLabel.setText("Invalid item input: " + ex.getMessage());
            errorLabel.setVisible(true);
        }
    }

    private void updateTotals() {
        Money subtotal = Money.ZERO;
        for (CartLineItem item : cartItems) {
            subtotal = subtotal.plus(item.lineTotal());
        }
        subtotalLabel.setText(subtotal.toFormattedString());

        Money billDiscount = Money.ZERO;
        try {
            String discText = overallDiscountField.getText().trim();
            if (!discText.isEmpty()) {
                billDiscount = Money.parse(discText);
            }
        } catch (Exception ignored) {}

        Money netTotal = subtotal.minus(billDiscount);
        if (netTotal.isNegative()) netTotal = Money.ZERO;

        grandTotalLabel.setText(netTotal.toFormattedString());
    }

    private void openPaymentDialog() {
        if (cartItems.isEmpty()) {
            errorLabel.setText("Cannot complete sale with an empty cart");
            errorLabel.setVisible(true);
            return;
        }

        Money totalAmount = Money.parse(grandTotalLabel.getText());
        Customer customer = customerComboBox.getValue();

        Dialog<PaymentSplit> dialog = new Dialog<>();
        dialog.setTitle("Payment & Settlement (Total: " + totalAmount.toFormattedString() + ")");
        dialog.setHeaderText("Enter Payment Breakdown (Split Payments Supported)");

        ButtonType confirmBtnType = new ButtonType("Confirm & Save", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(confirmBtnType, ButtonType.CANCEL);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20, 150, 10, 10));

        TextField cashField = new TextField(totalAmount.toRupeesString());
        TextField upiField = new TextField("0.00");
        TextField cardField = new TextField("0.00");
        TextField creditField = new TextField("0.00");

        grid.add(new Label("Cash (Rs):"), 0, 0);
        grid.add(cashField, 1, 0);
        grid.add(new Label("UPI (Rs):"), 0, 1);
        grid.add(upiField, 1, 1);
        grid.add(new Label("Card (Rs):"), 0, 2);
        grid.add(cardField, 1, 2);
        grid.add(new Label("Credit (Customer Ledger):"), 0, 3);
        grid.add(creditField, 1, 3);

        dialog.getDialogPane().setContent(grid);

        dialog.setResultConverter(dialogButton -> {
            if (dialogButton == confirmBtnType) {
                try {
                    List<PaymentInput> list = new ArrayList<>();
                    Money cash = Money.parse(cashField.getText());
                    Money upi = Money.parse(upiField.getText());
                    Money card = Money.parse(cardField.getText());
                    Money credit = Money.parse(creditField.getText());

                    if (cash.isPositive()) list.add(new PaymentInput(PaymentMode.CASH, cash, null));
                    if (upi.isPositive()) list.add(new PaymentInput(PaymentMode.UPI, upi, null));
                    if (card.isPositive()) list.add(new PaymentInput(PaymentMode.CARD, card, null));
                    if (credit.isPositive()) list.add(new PaymentInput(PaymentMode.CREDIT, credit, null));

                    return PaymentSplit.of(list);
                } catch (Exception ex) {
                    throw new RuntimeException("Invalid payment input: " + ex.getMessage());
                }
            }
            return null;
        });

        Optional<PaymentSplit> result = dialog.showAndWait();
        result.ifPresent(payments -> processSale(payments, totalAmount));
    }

    private void processSale(PaymentSplit payments, Money totalAmount) {
        try {
            Long customerId = customerComboBox.getValue() != null ? customerComboBox.getValue().id() : null;
            long userId = context.getCurrentUser() != null ? context.getCurrentUser().id() : 1L;
            Money billDiscount = overallDiscountField.getText().trim().isEmpty()
                    ? Money.ZERO : Money.parse(overallDiscountField.getText().trim());

            List<SaleItemInput> items = new ArrayList<>();
            for (CartLineItem ci : cartItems) {
                items.add(new SaleItemInput(ci.variantId(), ci.quantity(), ci.unitPrice(), ci.lineDiscount()));
            }

            Sale sale;
            if (saleMode == SaleMode.BILL) {
                sale = context.getGenerateBillService().generateBill(
                        customerId,
                        context.getClock().today(),
                        items,
                        billDiscount,
                        payments,
                        userId
                );

                // Generate PDF bill using BillPdfService
                Path billDir = Path.of(System.getProperty("user.home"), ".shopbilling", "bills");
                Files.createDirectories(billDir);
                String safeBillNumber = sale.billNumber() != null ? sale.billNumber().replace('/', '_') : ("sale-" + sale.id());
                Path pdfPath = billDir.resolve("Bill-" + safeBillNumber + ".pdf");
                try (OutputStream out = Files.newOutputStream(pdfPath)) {
                    context.getBillPdfService().generatePdfForSale(sale.id(), out);
                }

                Alert success = new Alert(Alert.AlertType.INFORMATION);
                success.setTitle("Bill Generated Successfully");
                success.setHeaderText("Bill Number: " + sale.billNumber());
                success.setContentText("Total: " + sale.totalAmount().toFormattedString() + "\nPDF Saved: " + pdfPath.toAbsolutePath());
                success.showAndWait();

            } else {
                sale = context.getRegisterSaleService().registerSale(
                        customerId,
                        context.getClock().today(),
                        items,
                        billDiscount,
                        payments,
                        userId
                );

                Alert success = new Alert(Alert.AlertType.INFORMATION);
                success.setTitle("Sale Registered Successfully");
                success.setHeaderText("Sale ID: #" + sale.id());
                success.setContentText("Stock deducted successfully.\nTotal: " + sale.totalAmount().toFormattedString());
                success.showAndWait();
            }

            // Clear cart
            cartItems.clear();
            customerComboBox.setValue(null);
            overallDiscountField.setText("0");
            updateTotals();

        } catch (Exception ex) {
            Alert error = new Alert(Alert.AlertType.ERROR, "Failed to save sale: " + ex.getMessage(), ButtonType.OK);
            error.setTitle("Sale Error");
            error.showAndWait();
        }
    }

    private void loadCustomers() {
        List<Customer> list = context.getCustomerSupplierService().getAllCustomers();
        customerComboBox.setItems(FXCollections.observableArrayList(list));
        customerComboBox.setCellFactory(param -> new ListCell<>() {
            @Override
            protected void updateItem(Customer c, boolean empty) {
                super.updateItem(c, empty);
                if (empty || c == null) setText(null);
                else setText(c.name() + " (" + (c.phone() != null ? c.phone() : "No Phone") + ") [" + c.customerType() + "]");
            }
        });
        customerComboBox.setButtonCell(customerComboBox.getCellFactory().call(null));
    }

    private void loadProducts() {
        List<ProductVariantWithInfo> items = new ArrayList<>();
        List<ProductWithVariants> products = context.getProductService().searchProducts(null);
        for (ProductWithVariants pwv : products) {
            String brandName = pwv.brand() != null ? pwv.brand().name() : "";
            for (ProductVariant v : pwv.variants()) {
                items.add(new ProductVariantWithInfo(pwv.product(), v, brandName, pwv.product().unitCode()));
            }
        }
        productComboBox.setItems(FXCollections.observableArrayList(items));
        productComboBox.setCellFactory(param -> new ListCell<>() {
            @Override
            protected void updateItem(ProductVariantWithInfo item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) setText(null);
                else setText(item.product().name() + " (" + item.variant().variantName() + ") - " + item.brandName() + " [₹" + item.variant().sellingPrice().toRupeesString() + "/" + item.unitCode() + "]");
            }
        });
        productComboBox.setButtonCell(productComboBox.getCellFactory().call(null));
    }

    private void showAddCustomerDialog() {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("Quick Customer Creation");
        dialog.setHeaderText("Enter Customer Name");
        dialog.showAndWait().ifPresent(name -> {
            if (!name.trim().isEmpty()) {
                Customer created = context.getCustomerSupplierService().createCustomer(
                        name.trim(), null, CustomerType.RETAIL, null, null, null
                );
                loadCustomers();
                customerComboBox.setValue(created);
            }
        });
    }

    public record CartLineItem(
            long variantId,
            String displayName,
            String unitCode,
            Quantity quantity,
            Money unitPrice,
            Money lineDiscount,
            Money lineTotal
    ) {}

    public record ProductVariantWithInfo(
            Product product,
            ProductVariant variant,
            String brandName,
            String unitCode
    ) {}
}
