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
import javafx.stage.FileChooser;

import java.io.File;
import java.io.FileWriter;
import java.time.LocalDate;
import java.util.*;

/**
 * Screen displaying sales transaction history, combinable filters, CSV export,
 * converting registered sales to bill, and customer returns (PRD Section 6, 7.5, 7.8, FR-20 - FR-25, FR-33 - FR-36).
 */
public class SalesScreen {

    private final ScreenManager screenManager;
    private final AppContext context;

    private final ObservableList<SalesSummaryRow> tableData = FXCollections.observableArrayList();
    private final TableView<SalesSummaryRow> tableView = new TableView<>(tableData);

    private final DatePicker dateFromPicker = new DatePicker();
    private final DatePicker dateToPicker = new DatePicker();
    private final ComboBox<String> saleModeComboBox = new ComboBox<>();
    private final ComboBox<String> customerTypeComboBox = new ComboBox<>();

    private final Label grossSalesLabel = new Label("₹0.00");
    private final Label totalReturnsLabel = new Label("₹0.00");
    private final Label netSalesLabel = new Label("₹0.00");
    private final Label transactionCountLabel = new Label("0 transactions");

    public SalesScreen(ScreenManager screenManager) {
        this.screenManager = Objects.requireNonNull(screenManager, "ScreenManager cannot be null");
        this.context = screenManager.getContext();
    }

    public Parent getView() {
        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color: #f8fafc;");

        // 1. TOP BAR: Back button + Title
        HBox topBar = new HBox(15);
        topBar.setAlignment(Pos.CENTER_LEFT);
        topBar.setPadding(new Insets(15, 25, 15, 25));
        topBar.setStyle("-fx-background-color: #ffffff; -fx-border-color: #e2e8f0; -fx-border-width: 0 0 1 0;");

        Button backBtn = new Button("← Back to Home");
        backBtn.setStyle("-fx-background-color: #f1f5f9; -fx-border-color: #cbd5e1; -fx-border-radius: 4;");
        backBtn.setOnAction(e -> screenManager.showHome());

        Label title = new Label("Sales History & Returns");
        title.setStyle("-fx-font-size: 20px; -fx-font-weight: bold; -fx-text-fill: #0f172a;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button exportCsvBtn = new Button("Export to CSV");
        exportCsvBtn.setStyle("-fx-background-color: #0284c7; -fx-text-fill: white; -fx-font-weight: bold; -fx-background-radius: 4;");
        exportCsvBtn.setOnAction(e -> exportToCsv());

        topBar.getChildren().addAll(backBtn, title, spacer, exportCsvBtn);
        root.setTop(topBar);

        // 2. CENTER: Filter Controls + Summary Ribbon + Table
        VBox centerBox = new VBox(15);
        centerBox.setPadding(new Insets(15, 25, 15, 25));

        // Filters Bar (FR-33)
        HBox filterBar = new HBox(12);
        filterBar.setAlignment(Pos.CENTER_LEFT);
        filterBar.setPadding(new Insets(12));
        filterBar.setStyle("-fx-background-color: #ffffff; -fx-border-color: #e2e8f0; -fx-border-radius: 6;");

        dateFromPicker.setPromptText("From Date");
        dateToPicker.setPromptText("To Date");

        saleModeComboBox.setItems(FXCollections.observableArrayList("ALL MODES", "BILL ONLY", "REGISTERED ONLY"));
        saleModeComboBox.setValue("ALL MODES");

        customerTypeComboBox.setItems(FXCollections.observableArrayList("ALL CUSTOMERS", "RETAIL", "TECHNICIAN"));
        customerTypeComboBox.setValue("ALL CUSTOMERS");

        Button applyFilterBtn = new Button("Apply Filters");
        applyFilterBtn.setStyle("-fx-background-color: #2563eb; -fx-text-fill: white; -fx-font-weight: bold; -fx-background-radius: 4;");
        applyFilterBtn.setOnAction(e -> loadData());

        Button resetBtn = new Button("Reset");
        resetBtn.setStyle("-fx-background-color: #f1f5f9; -fx-border-color: #cbd5e1; -fx-border-radius: 4;");
        resetBtn.setOnAction(e -> {
            dateFromPicker.setValue(null);
            dateToPicker.setValue(null);
            saleModeComboBox.setValue("ALL MODES");
            customerTypeComboBox.setValue("ALL CUSTOMERS");
            loadData();
        });

        filterBar.getChildren().addAll(
                new Label("From:"), dateFromPicker,
                new Label("To:"), dateToPicker,
                saleModeComboBox,
                customerTypeComboBox,
                applyFilterBtn,
                resetBtn
        );

        // Summary Ribbon (FR-35)
        HBox summaryRibbon = new HBox(25);
        summaryRibbon.setPadding(new Insets(12, 20, 12, 20));
        summaryRibbon.setStyle("-fx-background-color: #ffffff; -fx-border-color: #e2e8f0; -fx-border-radius: 6;");
        summaryRibbon.setAlignment(Pos.CENTER_LEFT);

        grossSalesLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #1e293b;");
        totalReturnsLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #dc2626;");
        netSalesLabel.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: #059669;");
        transactionCountLabel.setStyle("-fx-text-fill: #64748b; -fx-font-size: 13px;");

        summaryRibbon.getChildren().addAll(
                new VBox(2, new Label("Gross Sales"), grossSalesLabel),
                new Separator(javafx.geometry.Orientation.VERTICAL),
                new VBox(2, new Label("Returns"), totalReturnsLabel),
                new Separator(javafx.geometry.Orientation.VERTICAL),
                new VBox(2, new Label("Net Sales Turnover"), netSalesLabel),
                new Separator(javafx.geometry.Orientation.VERTICAL),
                new VBox(2, new Label("Volume"), transactionCountLabel)
        );

        // Table
        setupTable();
        VBox.setVgrow(tableView, Priority.ALWAYS);

        // Action Buttons Row: Convert to Bill & Process Return
        HBox actionRow = new HBox(15);
        actionRow.setAlignment(Pos.CENTER_LEFT);

        Button convertToBillBtn = new Button("Convert Registered Sale to Bill");
        convertToBillBtn.setStyle("-fx-background-color: #0284c7; -fx-text-fill: white; -fx-font-weight: bold; -fx-background-radius: 4;");
        convertToBillBtn.setOnAction(e -> handleConvertToBill());
        if (!context.isOwner()) {
            convertToBillBtn.setDisable(true);
        }

        Button returnBtn = new Button("Process Return / Credit Note");
        returnBtn.setStyle("-fx-background-color: #f59e0b; -fx-text-fill: white; -fx-font-weight: bold; -fx-background-radius: 4;");
        returnBtn.setOnAction(e -> handleProcessReturn());

        actionRow.getChildren().addAll(convertToBillBtn, returnBtn);

        centerBox.getChildren().addAll(filterBar, summaryRibbon, tableView, actionRow);
        root.setCenter(centerBox);

        // Initial Data Load
        loadData();

        return root;
    }

    private void setupTable() {
        TableColumn<SalesSummaryRow, String> colId = new TableColumn<>("Sale ID");
        colId.setCellValueFactory(d -> new SimpleStringProperty("#" + d.getValue().saleId()));
        colId.setPrefWidth(70);

        TableColumn<SalesSummaryRow, String> colMode = new TableColumn<>("Mode");
        colMode.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().saleMode().name()));
        colMode.setPrefWidth(90);

        TableColumn<SalesSummaryRow, String> colBillNo = new TableColumn<>("Bill Number");
        colBillNo.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().billNumber() != null ? d.getValue().billNumber() : "-"));
        colBillNo.setPrefWidth(120);

        TableColumn<SalesSummaryRow, String> colDate = new TableColumn<>("Date");
        colDate.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().saleDate().toString()));
        colDate.setPrefWidth(100);

        TableColumn<SalesSummaryRow, String> colCust = new TableColumn<>("Customer");
        colCust.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().customerName()));
        colCust.setPrefWidth(180);

        TableColumn<SalesSummaryRow, String> colCustType = new TableColumn<>("Type");
        colCustType.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().customerType() != null ? d.getValue().customerType().name() : "RETAIL"));
        colCustType.setPrefWidth(100);

        TableColumn<SalesSummaryRow, String> colSubtotal = new TableColumn<>("Subtotal (Rs)");
        colSubtotal.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().subtotal().toRupeesString()));
        colSubtotal.setPrefWidth(100);

        TableColumn<SalesSummaryRow, String> colDisc = new TableColumn<>("Discount (Rs)");
        colDisc.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().discount().toRupeesString()));
        colDisc.setPrefWidth(100);

        TableColumn<SalesSummaryRow, String> colTotal = new TableColumn<>("Total (Rs)");
        colTotal.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().totalAmount().toRupeesString()));
        colTotal.setPrefWidth(110);

        TableColumn<SalesSummaryRow, String> colModes = new TableColumn<>("Payments");
        colModes.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().paymentModes()));
        colModes.setPrefWidth(120);

        tableView.getColumns().addAll(colId, colMode, colBillNo, colDate, colCust, colCustType, colSubtotal, colDisc, colTotal, colModes);
    }

    private void loadData() {
        LocalDate from = dateFromPicker.getValue();
        LocalDate to = dateToPicker.getValue();

        SaleMode mode = null;
        if ("BILL ONLY".equals(saleModeComboBox.getValue())) mode = SaleMode.BILL;
        else if ("REGISTERED ONLY".equals(saleModeComboBox.getValue())) mode = SaleMode.REGISTERED;

        CustomerType custType = null;
        if ("RETAIL".equals(customerTypeComboBox.getValue())) custType = CustomerType.RETAIL;
        else if ("TECHNICIAN".equals(customerTypeComboBox.getValue())) custType = CustomerType.TECHNICIAN;

        SalesReportFilter filter = new SalesReportFilter(from, to, mode, null, null, null, custType, null);
        SalesReportResult result = context.getReportService().generateSalesReport(filter);

        tableData.setAll(result.rows());
        grossSalesLabel.setText(result.grossSales().toFormattedString());
        totalReturnsLabel.setText(result.totalReturns().toFormattedString());
        netSalesLabel.setText(result.netSales().toFormattedString());
        transactionCountLabel.setText(String.format("%d total (%d bills, %d reg)",
                result.totalTransactions(), result.billedTransactions(), result.registeredTransactions()));
    }

    private void handleConvertToBill() {
        SalesSummaryRow selected = tableView.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showAlert(Alert.AlertType.WARNING, "Please select a registered sale from the table");
            return;
        }

        if (selected.saleMode() == SaleMode.BILL) {
            showAlert(Alert.AlertType.INFORMATION, "Sale #" + selected.saleId() + " is already an official bill (" + selected.billNumber() + ")");
            return;
        }

        try {
            long userId = context.getCurrentUser() != null ? context.getCurrentUser().id() : 1L;
            Sale billed = context.getGenerateBillService().convertToBill(selected.saleId(), userId);
            showAlert(Alert.AlertType.INFORMATION, "Successfully converted to official bill: " + billed.billNumber());
            loadData();
        } catch (Exception ex) {
            showAlert(Alert.AlertType.ERROR, "Conversion failed: " + ex.getMessage());
        }
    }

    private void handleProcessReturn() {
        SalesSummaryRow selected = tableView.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showAlert(Alert.AlertType.WARNING, "Please select a sale to return against");
            return;
        }

        Optional<Sale> optSale = context.getGenerateBillService().getSaleById(selected.saleId());
        if (optSale.isEmpty()) {
            showAlert(Alert.AlertType.ERROR, "Sale not found");
            return;
        }

        Sale sale = optSale.get();
        if (sale.items().isEmpty()) {
            showAlert(Alert.AlertType.ERROR, "Sale has no line items to return");
            return;
        }

        Dialog<Boolean> dialog = new Dialog<>();
        dialog.setTitle("Process Customer Return (Sale #" + sale.id() + ")");
        dialog.setHeaderText("Select item and return quantity");

        ButtonType confirmBtnType = new ButtonType("Confirm Return", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(confirmBtnType, ButtonType.CANCEL);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20, 150, 10, 10));

        ComboBox<SaleItem> itemCombo = new ComboBox<>(FXCollections.observableArrayList(sale.items()));
        itemCombo.setCellFactory(p -> new ListCell<>() {
            @Override
            protected void updateItem(SaleItem item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) setText(null);
                else setText("Item #" + item.id() + " - Qty: " + item.quantity().toDisplayString() + " @ " + item.unitPrice().toFormattedString());
            }
        });
        itemCombo.setButtonCell(itemCombo.getCellFactory().call(null));
        itemCombo.setValue(sale.items().get(0));

        TextField returnQtyField = new TextField("1");
        ComboBox<ReturnCondition> conditionCombo = new ComboBox<>(FXCollections.observableArrayList(ReturnCondition.values()));
        conditionCombo.setValue(ReturnCondition.SELLABLE);

        ComboBox<PaymentMode> refundModeCombo = new ComboBox<>(FXCollections.observableArrayList(
                PaymentMode.CASH, PaymentMode.UPI, PaymentMode.CREDIT
        ));
        refundModeCombo.setValue(PaymentMode.CASH);

        TextField reasonField = new TextField("Customer Return");

        grid.add(new Label("Line Item:"), 0, 0);
        grid.add(itemCombo, 1, 0);
        grid.add(new Label("Return Qty:"), 0, 1);
        grid.add(returnQtyField, 1, 1);
        grid.add(new Label("Item Condition:"), 0, 2);
        grid.add(conditionCombo, 1, 2);
        grid.add(new Label("Refund Mode:"), 0, 3);
        grid.add(refundModeCombo, 1, 3);
        grid.add(new Label("Reason:"), 0, 4);
        grid.add(reasonField, 1, 4);

        dialog.getDialogPane().setContent(grid);

        dialog.setResultConverter(btn -> {
            if (btn == confirmBtnType) {
                try {
                    SaleItem si = itemCombo.getValue();
                    Quantity qty = Quantity.of(new java.math.BigDecimal(returnQtyField.getText().trim()));
                    ReturnCondition cond = conditionCombo.getValue();
                    PaymentMode refundMode = refundModeCombo.getValue();
                    String reason = reasonField.getText();

                    long userId = context.getCurrentUser() != null ? context.getCurrentUser().id() : 1L;

                    ReturnRecord record = context.getReturnService().processReturn(
                            sale.id(),
                            context.getClock().today(),
                            List.of(new ReturnItemInput(si.id(), qty, cond, reason)),
                            refundMode,
                            reason,
                            userId
                    );

                    String msg = "Return processed successfully!\nRefund: " + record.totalRefund().toFormattedString();
                    if (record.creditNoteNumber() != null) {
                        msg += "\nCredit Note Number: " + record.creditNoteNumber();
                    }
                    showAlert(Alert.AlertType.INFORMATION, msg);
                    loadData();
                    return true;
                } catch (Exception ex) {
                    showAlert(Alert.AlertType.ERROR, "Return failed: " + ex.getMessage());
                    return false;
                }
            }
            return null;
        });

        dialog.showAndWait();
    }

    private void exportToCsv() {
        SalesReportFilter filter = new SalesReportFilter(
                dateFromPicker.getValue(), dateToPicker.getValue(), null, null, null, null, null, null
        );
        SalesReportResult report = context.getReportService().generateSalesReport(filter);

        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Save Sales Report CSV");
        fileChooser.setInitialFileName("sales_report.csv");
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV Files (*.csv)", "*.csv"));

        File file = fileChooser.showSaveDialog(screenManager.getStage());
        if (file != null) {
            try (FileWriter fw = new FileWriter(file)) {
                context.getReportService().exportToCsv(report, fw);
                showAlert(Alert.AlertType.INFORMATION, "Sales report CSV exported to:\n" + file.getAbsolutePath());
            } catch (Exception ex) {
                showAlert(Alert.AlertType.ERROR, "CSV export failed: " + ex.getMessage());
            }
        }
    }

    private void showAlert(Alert.AlertType type, String content) {
        Alert alert = new Alert(type, content, ButtonType.OK);
        alert.showAndWait();
    }
}
