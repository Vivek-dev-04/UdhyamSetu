package com.shopbilling.ui;

import com.shopbilling.model.*;
import com.shopbilling.service.ShopSettingsService;
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

        Button shopDetailsBtn = new Button("🏢 Shop / Bill Profile");
        shopDetailsBtn.setStyle("-fx-background-color: #f8fafc; -fx-border-color: #cbd5e1; -fx-text-fill: #1e3a8a; -fx-font-weight: bold; -fx-border-radius: 4; -fx-cursor: hand;");
        shopDetailsBtn.setOnAction(e -> showShopBillingDetailsDialog());

        titleRow.getChildren().addAll(backBtn, titleLabel, modeBadge, spacer, shopDetailsBtn);

        // Customer selection row & B2B/B2C Billing Details (FR-10)
        HBox customerRow = new HBox(10);
        customerRow.setAlignment(Pos.CENTER_LEFT);

        Label custLabel = new Label("Billed To / Party:");
        custLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 13px;");

        customerComboBox.setPrefWidth(320);
        customerComboBox.setPromptText("Select Customer / Firm (Default: Walk-in)");

        Button newCustBtn = new Button("➕ Customer / Firm Form");
        newCustBtn.setStyle("-fx-background-color: #f0fdf4; -fx-border-color: #86efac; -fx-text-fill: #166534; -fx-font-weight: bold; -fx-border-radius: 4;");
        newCustBtn.setOnAction(e -> showCustomerBillingDialog(null));

        Button editCustBtn = new Button("✏️ Edit Details");
        editCustBtn.setStyle("-fx-background-color: #f8fafc; -fx-border-color: #cbd5e1; -fx-border-radius: 4;");
        editCustBtn.setDisable(true);
        editCustBtn.setOnAction(e -> {
            if (customerComboBox.getValue() != null) {
                showCustomerBillingDialog(customerComboBox.getValue());
            }
        });

        Button walkInBtn = new Button("✕ Walk-in");
        walkInBtn.setStyle("-fx-background-color: #f8fafc; -fx-border-color: #cbd5e1; -fx-border-radius: 4;");
        walkInBtn.setOnAction(e -> customerComboBox.setValue(null));

        customerRow.getChildren().addAll(custLabel, customerComboBox, newCustBtn, editCustBtn, walkInBtn);

        // Buyer details status card
        HBox customerDetailsCard = new HBox(12);
        customerDetailsCard.setAlignment(Pos.CENTER_LEFT);
        customerDetailsCard.setPadding(new Insets(6, 12, 6, 12));
        customerDetailsCard.setStyle("-fx-background-color: #f8fafc; -fx-background-radius: 6; -fx-border-color: #e2e8f0; -fx-border-radius: 6;");

        Label customerBadge = new Label("🛒 B2C Walk-in Customer");
        customerBadge.setStyle("-fx-font-weight: bold; -fx-font-size: 12px; -fx-text-fill: #475569;");

        Label customerDetailsText = new Label("Retail Counter Sale • Cash Memo • Tax invoice without firm GSTIN");
        customerDetailsText.setStyle("-fx-text-fill: #64748b; -fx-font-size: 12px;");

        customerDetailsCard.getChildren().addAll(customerBadge, customerDetailsText);

        customerComboBox.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal == null) {
                editCustBtn.setDisable(true);
                customerBadge.setText("🛒 B2C Walk-in Customer");
                customerBadge.setStyle("-fx-font-weight: bold; -fx-font-size: 12px; -fx-text-fill: #475569;");
                customerDetailsText.setText("Retail Counter Sale • Cash Memo • Tax invoice without firm GSTIN");
                customerDetailsCard.setStyle("-fx-background-color: #f8fafc; -fx-background-radius: 6; -fx-border-color: #e2e8f0; -fx-border-radius: 6;");
            } else if (newVal.isFirm()) {
                editCustBtn.setDisable(false);
                customerBadge.setText("🏢 B2B TAX INVOICE — FIRM");
                customerBadge.setStyle("-fx-font-weight: bold; -fx-font-size: 12px; -fx-text-fill: #1e40af;");
                String gstinStr = newVal.getGstin() != null ? newVal.getGstin() : "Pending GSTIN";
                String stateStr = newVal.getStateName() + " (" + newVal.getStateCode() + ")";
                String phoneStr = newVal.phone() != null ? " • Ph: " + newVal.phone() : "";
                String addrStr = newVal.address() != null ? " • " + newVal.address() : "";
                customerDetailsText.setText(newVal.name() + " | GSTIN: " + gstinStr + " | State: " + stateStr + phoneStr + addrStr);
                customerDetailsCard.setStyle("-fx-background-color: #eff6ff; -fx-background-radius: 6; -fx-border-color: #bfdbfe; -fx-border-radius: 6;");
            } else {
                editCustBtn.setDisable(false);
                customerBadge.setText("👤 INDIVIDUAL CUSTOMER (" + newVal.customerType() + ")");
                customerBadge.setStyle("-fx-font-weight: bold; -fx-font-size: 12px; -fx-text-fill: #166534;");
                String phoneStr = newVal.phone() != null ? " • Ph: " + newVal.phone() : " • No phone";
                String addrStr = newVal.address() != null ? " • " + newVal.address() : "";
                customerDetailsText.setText(newVal.name() + phoneStr + addrStr);
                customerDetailsCard.setStyle("-fx-background-color: #f0fdf4; -fx-background-radius: 6; -fx-border-color: #bbf7d0; -fx-border-radius: 6;");
            }
        });

        loadCustomers();

        errorLabel.setStyle("-fx-text-fill: #dc2626; -fx-font-weight: bold;");
        errorLabel.setVisible(false);

        topBox.getChildren().addAll(titleRow, customerRow, customerDetailsCard, errorLabel);
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
        overallDiscountField.textProperty().addListener((obs, oldVal, newVal) -> updateTotals());
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
        Customer current = customerComboBox.getValue();
        customerComboBox.setItems(FXCollections.observableArrayList(list));
        customerComboBox.setCellFactory(param -> new ListCell<>() {
            @Override
            protected void updateItem(Customer c, boolean empty) {
                super.updateItem(c, empty);
                if (empty || c == null) {
                    setText(null);
                } else if (c.isFirm()) {
                    String gst = c.getGstin() != null ? " [GSTIN: " + c.getGstin() + "]" : " [FIRM]";
                    setText("🏢 " + c.name() + gst + " • " + (c.phone() != null ? c.phone() : "No Phone"));
                } else {
                    setText("👤 " + c.name() + " (" + (c.phone() != null ? c.phone() : "No Phone") + ") [" + c.customerType() + "]");
                }
            }
        });
        customerComboBox.setButtonCell(customerComboBox.getCellFactory().call(null));
        if (current != null) {
            for (Customer c : list) {
                if (Objects.equals(c.id(), current.id())) {
                    customerComboBox.setValue(c);
                    break;
                }
            }
        }
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

    private void showCustomerBillingDialog(Customer customerToEdit) {
        Dialog<Customer> dialog = new Dialog<>();
        dialog.setTitle(customerToEdit == null ? "Customer & Firm Billing Form" : "Edit Customer / Firm Billing Details");
        dialog.setHeaderText(customerToEdit == null
                ? "Register B2B Firm (Tax Invoice) or Individual Customer"
                : "Update Billing Information for " + customerToEdit.name());

        ButtonType saveBtnType = new ButtonType("Save & Select", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveBtnType, ButtonType.CANCEL);

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(10);
        grid.setPadding(new Insets(15, 20, 15, 20));

        // Radio toggle for Firm (B2B) vs Individual (B2C)
        ToggleGroup typeGroup = new ToggleGroup();
        RadioButton firmRadio = new RadioButton("🏢 Firm / Business (B2B Tax Invoice)");
        firmRadio.setToggleGroup(typeGroup);
        firmRadio.setStyle("-fx-font-weight: bold; -fx-text-fill: #1e40af;");

        RadioButton indRadio = new RadioButton("👤 Individual Customer (Retail / Technician)");
        indRadio.setToggleGroup(typeGroup);
        indRadio.setStyle("-fx-font-weight: bold; -fx-text-fill: #166534;");

        HBox radioBox = new HBox(20, firmRadio, indRadio);
        radioBox.setPadding(new Insets(0, 0, 8, 0));

        Label nameLabel = new Label("Firm / Company Name *:");
        nameLabel.setStyle("-fx-font-weight: bold;");
        TextField nameField = new TextField();
        nameField.setPromptText("e.g. NAVEEN ELECTRICAL & SANITARY");
        nameField.setPrefWidth(350);

        Label phoneLabel = new Label("Phone / Contact Number:");
        TextField phoneField = new TextField();
        phoneField.setPromptText("e.g. 9461586959");

        Label gstinLabel = new Label("GSTIN / UIN (15 Digits):");
        gstinLabel.setStyle("-fx-font-weight: bold;");
        TextField gstinField = new TextField();
        gstinField.setPromptText("e.g. 08ACDPT3960H1ZC");

        Label gstinFeedback = new Label("");
        gstinFeedback.setStyle("-fx-font-size: 11px;");

        Label stateLabel = new Label("State & State Code:");
        ComboBox<String> stateCombo = new ComboBox<>();
        stateCombo.setPrefWidth(350);
        List<String> stateList = new ArrayList<>();
        StateCodeHelper.getAllStates().forEach((code, sName) -> stateList.add(sName + " (" + code + ")"));
        stateCombo.setItems(FXCollections.observableArrayList(stateList));
        stateCombo.setValue("Rajasthan (08)");

        Label addrLabel = new Label("Billing / Delivery Address:");
        TextArea addressField = new TextArea();
        addressField.setPromptText("e.g. 10 / 185 , SAWARN PATH, MANSAROVER, JAIPUR (RAJASTHAN)");
        addressField.setPrefRowCount(2);
        addressField.setWrapText(true);

        Label subTypeLabel = new Label("Customer Sub-Type:");
        ComboBox<CustomerType> subTypeCombo = new ComboBox<>(FXCollections.observableArrayList(CustomerType.RETAIL, CustomerType.TECHNICIAN));
        subTypeCombo.setValue(CustomerType.TECHNICIAN);

        Label creditLimitLabel = new Label("Credit Limit (₹, Optional):");
        TextField creditLimitField = new TextField();
        creditLimitField.setPromptText("e.g. 50000");

        Label notesLabel = new Label("Additional Notes / Remarks:");
        TextField notesField = new TextField();
        notesField.setPromptText("Optional remarks");

        // Dynamic behavior on radio toggle
        typeGroup.selectedToggleProperty().addListener((obs, oldVal, newVal) -> {
            if (firmRadio.isSelected()) {
                nameLabel.setText("Firm / Business Name *:");
                nameField.setPromptText("e.g. NAVEEN ELECTRICAL & SANITARY");
                gstinLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: #1e40af;");
                subTypeCombo.setValue(CustomerType.TECHNICIAN);
            } else {
                nameLabel.setText("Customer Full Name *:");
                nameField.setPromptText("e.g. Rajesh Sharma");
                gstinLabel.setStyle("-fx-font-weight: normal; -fx-text-fill: #374151;");
                subTypeCombo.setValue(CustomerType.RETAIL);
            }
        });

        // GSTIN uppercase & auto state detection
        gstinField.textProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) {
                String upper = newVal.toUpperCase().replaceAll("\\s+", "");
                if (!upper.equals(newVal)) {
                    gstinField.setText(upper);
                    return;
                }
                if (upper.length() == 15) {
                    if (upper.matches("\\d{2}[A-Z]{5}\\d{4}[A-Z]{1}[A-Z\\d]{1}[Z]{1}[A-Z\\d]{1}")) {
                        String code = upper.substring(0, 2);
                        String sName = StateCodeHelper.getStateName(code);
                        gstinFeedback.setText("✓ Valid Indian GSTIN (State: " + sName + ")");
                        gstinFeedback.setStyle("-fx-text-fill: #16a34a; -fx-font-size: 11px;");
                        for (String item : stateCombo.getItems()) {
                            if (item.contains("(" + code + ")")) {
                                stateCombo.setValue(item);
                                break;
                            }
                        }
                    } else {
                        gstinFeedback.setText("⚠ Invalid GSTIN format (Expected 15 chars e.g. 08ACDPT3960H1ZC)");
                        gstinFeedback.setStyle("-fx-text-fill: #dc2626; -fx-font-size: 11px;");
                    }
                } else if (!upper.isEmpty()) {
                    gstinFeedback.setText("Enter 15-character GSTIN (" + upper.length() + "/15)");
                    gstinFeedback.setStyle("-fx-text-fill: #6b7280; -fx-font-size: 11px;");
                } else {
                    gstinFeedback.setText("");
                }
            }
        });

        // Default or pre-populated state
        if (customerToEdit != null) {
            if (customerToEdit.isFirm()) {
                firmRadio.setSelected(true);
            } else {
                indRadio.setSelected(true);
            }
            nameField.setText(customerToEdit.name());
            if (customerToEdit.phone() != null) phoneField.setText(customerToEdit.phone());
            if (customerToEdit.address() != null) addressField.setText(customerToEdit.address());
            if (customerToEdit.creditLimit() != null) creditLimitField.setText(customerToEdit.creditLimit().toRupeesString());
            String existingGst = customerToEdit.getGstin();
            if (existingGst != null) gstinField.setText(existingGst);
            String existingCode = customerToEdit.getStateCode();
            for (String item : stateCombo.getItems()) {
                if (item.contains("(" + existingCode + ")")) {
                    stateCombo.setValue(item);
                    break;
                }
            }
            subTypeCombo.setValue(customerToEdit.customerType());
            if (customerToEdit.notes() != null) {
                String cleanNotes = customerToEdit.notes()
                        .replaceAll("\\[FIRM\\]", "")
                        .replaceAll("\\[INDIVIDUAL\\]", "")
                        .replaceAll("GSTIN:\\s*[A-Z0-9]+", "")
                        .replaceAll("State:\\s*[^|]+", "")
                        .replaceAll("\\|\\s*\\|", "")
                        .replaceAll("^\\s*\\|\\s*", "")
                        .replaceAll("\\s*\\|\\s*$", "")
                        .trim();
                notesField.setText(cleanNotes);
            }
        } else {
            firmRadio.setSelected(true);
        }

        // Layout
        grid.add(radioBox, 0, 0, 2, 1);
        grid.add(nameLabel, 0, 1);
        grid.add(nameField, 1, 1);
        grid.add(phoneLabel, 0, 2);
        grid.add(phoneField, 1, 2);
        grid.add(gstinLabel, 0, 3);
        VBox gstinBox = new VBox(2, gstinField, gstinFeedback);
        grid.add(gstinBox, 1, 3);
        grid.add(stateLabel, 0, 4);
        grid.add(stateCombo, 1, 4);
        grid.add(addrLabel, 0, 5);
        grid.add(addressField, 1, 5);
        grid.add(subTypeLabel, 0, 6);
        grid.add(subTypeCombo, 1, 6);
        grid.add(creditLimitLabel, 0, 7);
        grid.add(creditLimitField, 1, 7);
        grid.add(notesLabel, 0, 8);
        grid.add(notesField, 1, 8);

        dialog.getDialogPane().setContent(grid);

        dialog.setResultConverter(btn -> {
            if (btn == saveBtnType) {
                String name = nameField.getText().trim();
                if (name.isEmpty()) {
                    new Alert(Alert.AlertType.ERROR, "Name cannot be empty.", ButtonType.OK).showAndWait();
                    return null;
                }

                String phone = phoneField.getText().trim();
                if (phone.isEmpty()) phone = null;

                String address = addressField.getText().trim();
                if (address.isEmpty()) address = null;

                String gstin = gstinField.getText().trim().toUpperCase();
                if (!gstin.isEmpty() && gstin.length() != 15) {
                    new Alert(Alert.AlertType.WARNING, "GSTIN should be 15 characters long if provided.", ButtonType.OK).showAndWait();
                }

                String selectedState = stateCombo.getValue();
                String stateName = "Rajasthan";
                String stateCode = "08";
                if (selectedState != null && selectedState.contains("(") && selectedState.contains(")")) {
                    stateName = selectedState.substring(0, selectedState.indexOf('(')).trim();
                    stateCode = selectedState.substring(selectedState.indexOf('(') + 1, selectedState.indexOf(')')).trim();
                }

                Money creditLimit = null;
                if (!creditLimitField.getText().trim().isEmpty()) {
                    try {
                        creditLimit = Money.parse(creditLimitField.getText().trim());
                    } catch (Exception ex) {
                        new Alert(Alert.AlertType.ERROR, "Invalid credit limit: " + ex.getMessage(), ButtonType.OK).showAndWait();
                        return null;
                    }
                }

                String extraNotes = notesField.getText().trim();

                // Format structured notes
                String finalNotes;
                if (firmRadio.isSelected()) {
                    String gstPart = !gstin.isEmpty() ? ("GSTIN: " + gstin) : "GSTIN: UNREGISTERED";
                    String stPart = "State: " + stateName + " (" + stateCode + ")";
                    finalNotes = "[FIRM] " + gstPart + " | " + stPart + (extraNotes.isEmpty() ? "" : " | " + extraNotes);
                } else {
                    if (!gstin.isEmpty()) {
                        finalNotes = "[INDIVIDUAL] GSTIN: " + gstin + " | State: " + stateName + " (" + stateCode + ")" + (extraNotes.isEmpty() ? "" : " | " + extraNotes);
                    } else if (!extraNotes.isEmpty()) {
                        finalNotes = extraNotes;
                    } else {
                        finalNotes = null;
                    }
                }

                try {
                    Customer saved;
                    if (customerToEdit == null) {
                        saved = context.getCustomerSupplierService().createCustomer(
                                name, phone, subTypeCombo.getValue(), address, creditLimit, finalNotes
                        );
                    } else {
                        saved = context.getCustomerSupplierService().updateCustomer(
                                customerToEdit.id(), name, phone, subTypeCombo.getValue(), address, creditLimit, finalNotes
                        );
                    }
                    loadCustomers();
                    customerComboBox.setValue(saved);
                    return saved;
                } catch (Exception ex) {
                    new Alert(Alert.AlertType.ERROR, "Failed to save customer/firm: " + ex.getMessage(), ButtonType.OK).showAndWait();
                    return null;
                }
            }
            return null;
        });

        dialog.showAndWait();
    }

    private void showShopBillingDetailsDialog() {
        Dialog<Boolean> dialog = new Dialog<>();
        dialog.setTitle("Shop & Firm Billing Profile");
        dialog.setHeaderText("Set your firm name, GSTIN, address, bank, and UPI details.\nThese details appear on all generated A4 bills and the payment QR code.");

        ButtonType saveBtnType = new ButtonType("Save & Update Bills", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveBtnType, ButtonType.CANCEL);

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(10);
        grid.setPadding(new Insets(15, 20, 15, 20));

        ShopSettingsService.ShopBillingDetails current = context.getShopSettingsService().getShopBillingDetails();

        TextField nameField = new TextField(current.shopName());
        nameField.setPrefWidth(350);

        TextField taglineField = new TextField(current.shopTagline());
        taglineField.setPromptText("e.g. Electrical Goods & Hardware Retail");

        TextField addressField = new TextField(current.shopAddress());
        TextField phoneField = new TextField(current.shopPhone());
        TextField emailField = new TextField(current.shopEmail());

        TextField gstinField = new TextField(current.shopGstin());
        gstinField.setPromptText("15-digit GSTIN (e.g. 08AACFM6417B1ZN)");

        ComboBox<String> stateCombo = new ComboBox<>();
        StateCodeHelper.getAllStates().forEach((code, state) -> stateCombo.getItems().add(state + " (" + code + ")"));
        String currentStateStr = current.shopStateName() + " (" + current.shopStateCode() + ")";
        if (stateCombo.getItems().contains(currentStateStr)) {
            stateCombo.setValue(currentStateStr);
        } else {
            stateCombo.setValue("Rajasthan (08)");
        }

        TextField panField = new TextField(current.shopPan());
        panField.setPromptText("10-digit PAN");

        TextField bankField = new TextField(current.shopBankName());
        bankField.setPromptText("Bank Name (e.g. PUNJAB NATIONAL BANK)");

        TextField acctField = new TextField(current.shopAccountNo());
        acctField.setPromptText("Bank Account Number");

        TextField ifscField = new TextField(current.shopIfsc());
        ifscField.setPromptText("IFSC Code (e.g. PUNB0006110)");

        TextField branchField = new TextField(current.shopBranch());
        branchField.setPromptText("Branch Name");

        TextField upiField = new TextField(current.shopUpiId());
        upiField.setPromptText("UPI ID (e.g. 9829053822@upi)");

        // Auto-extract state and PAN when GSTIN is typed
        gstinField.textProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) {
                String clean = newVal.trim().toUpperCase();
                if (!clean.equals(newVal)) {
                    gstinField.setText(clean);
                }
                if (clean.length() >= 2) {
                    String stateCode = clean.substring(0, 2);
                    String stateName = StateCodeHelper.getStateName(stateCode);
                    String match = stateName + " (" + stateCode + ")";
                    if (stateCombo.getItems().contains(match)) {
                        stateCombo.setValue(match);
                    }
                }
                if (clean.length() >= 12) {
                    panField.setText(clean.substring(2, 12));
                }
            }
        });

        ifscField.textProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && !newVal.equals(newVal.toUpperCase())) {
                ifscField.setText(newVal.toUpperCase());
            }
        });

        int row = 0;
        Label firmHeader = new Label("1. FIRM & BUSINESS INFORMATION");
        firmHeader.setStyle("-fx-font-weight: bold; -fx-text-fill: #1e3a8a;");
        grid.add(firmHeader, 0, row++, 2, 1);

        grid.add(new Label("Firm / Shop Name: *"), 0, row);
        grid.add(nameField, 1, row++);

        grid.add(new Label("Tagline / Nature:"), 0, row);
        grid.add(taglineField, 1, row++);

        grid.add(new Label("Shop Address:"), 0, row);
        grid.add(addressField, 1, row++);

        grid.add(new Label("Phone / Mobile:"), 0, row);
        grid.add(phoneField, 1, row++);

        grid.add(new Label("Email:"), 0, row);
        grid.add(emailField, 1, row++);

        grid.add(new Label("Shop GSTIN:"), 0, row);
        grid.add(gstinField, 1, row++);

        grid.add(new Label("State & Code:"), 0, row);
        grid.add(stateCombo, 1, row++);

        grid.add(new Label("PAN Number:"), 0, row);
        grid.add(panField, 1, row++);

        Label bankHeader = new Label("2. BANK & PAYMENT DETAILS (For QR Code & Bill)");
        bankHeader.setStyle("-fx-font-weight: bold; -fx-text-fill: #1e3a8a; -fx-padding: 8 0 0 0;");
        grid.add(bankHeader, 0, row++, 2, 1);

        grid.add(new Label("Bank Name:"), 0, row);
        grid.add(bankField, 1, row++);

        grid.add(new Label("Account No:"), 0, row);
        grid.add(acctField, 1, row++);

        grid.add(new Label("IFSC Code:"), 0, row);
        grid.add(ifscField, 1, row++);

        grid.add(new Label("Branch:"), 0, row);
        grid.add(branchField, 1, row++);

        grid.add(new Label("UPI ID (For Bill QR):"), 0, row);
        grid.add(upiField, 1, row++);

        dialog.getDialogPane().setContent(grid);

        dialog.setResultConverter(btn -> {
            if (btn == saveBtnType) {
                String firmName = nameField.getText().trim();
                if (firmName.isEmpty()) {
                    new Alert(Alert.AlertType.ERROR, "Firm / Shop Name cannot be blank", ButtonType.OK).showAndWait();
                    return false;
                }

                String selectedState = stateCombo.getValue();
                String stateName = "Rajasthan";
                String stateCode = "08";
                if (selectedState != null && selectedState.contains("(") && selectedState.contains(")")) {
                    stateName = selectedState.substring(0, selectedState.indexOf('(')).trim();
                    stateCode = selectedState.substring(selectedState.indexOf('(') + 1, selectedState.indexOf(')')).trim();
                }

                ShopSettingsService.ShopBillingDetails updated = new ShopSettingsService.ShopBillingDetails(
                        firmName,
                        taglineField.getText().trim(),
                        addressField.getText().trim(),
                        phoneField.getText().trim(),
                        emailField.getText().trim(),
                        gstinField.getText().trim(),
                        stateName,
                        stateCode,
                        panField.getText().trim(),
                        bankField.getText().trim(),
                        acctField.getText().trim(),
                        ifscField.getText().trim(),
                        branchField.getText().trim(),
                        upiField.getText().trim()
                );

                try {
                    long userId = context.getCurrentUser() != null ? context.getCurrentUser().id() : 1L;
                    context.getShopSettingsService().updateShopBillingDetails(userId, updated);
                    new Alert(Alert.AlertType.INFORMATION, "Shop & Firm details updated successfully!\nAll new bills will print with this firm name, GST, bank & QR code.", ButtonType.OK).showAndWait();
                    return true;
                } catch (Exception ex) {
                    new Alert(Alert.AlertType.ERROR, "Failed to update shop details: " + ex.getMessage(), ButtonType.OK).showAndWait();
                    return false;
                }
            }
            return false;
        });

        dialog.showAndWait();
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
