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

import java.util.*;

/**
 * Screen managing customer and supplier credit ledgers and overdue aging buckets
 * (PRD Section 7.6, FR-26 - FR-30).
 */
public class CreditLedgerScreen {

    private final ScreenManager screenManager;
    private final AppContext context;

    private final ObservableList<CustomerDuesSummary> customerDuesData = FXCollections.observableArrayList();
    private final TableView<CustomerDuesSummary> customerTable = new TableView<>(customerDuesData);

    private final ObservableList<SupplierPayableSummary> supplierData = FXCollections.observableArrayList();
    private final TableView<SupplierPayableSummary> supplierTable = new TableView<>(supplierData);

    public CreditLedgerScreen(ScreenManager screenManager) {
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

        Label title = new Label("Credit Ledgers & Overdue Aging");
        title.setStyle("-fx-font-size: 20px; -fx-font-weight: bold; -fx-text-fill: #0f172a;");

        topBar.getChildren().addAll(backBtn, title);
        root.setTop(topBar);

        // Center: Tabs for Customers and Suppliers
        TabPane tabPane = new TabPane();
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        Tab custTab = new Tab("Customer Receivables & Aging", buildCustomerTab());
        Tab suppTab = new Tab("Supplier Payables", buildSupplierTab());

        tabPane.getTabs().addAll(custTab, suppTab);
        root.setCenter(tabPane);

        loadCustomerData();
        loadSupplierData();

        return root;
    }

    private VBox buildCustomerTab() {
        VBox box = new VBox(15);
        box.setPadding(new Insets(15, 25, 15, 25));

        HBox actionRow = new HBox(15);
        actionRow.setAlignment(Pos.CENTER_LEFT);

        Button payBtn = new Button("Record Customer Payment");
        payBtn.setStyle("-fx-background-color: #16a34a; -fx-text-fill: white; -fx-font-weight: bold; -fx-background-radius: 4;");
        payBtn.setOnAction(e -> handleRecordCustomerPayment());

        Button statementBtn = new Button("View Customer Statement");
        statementBtn.setStyle("-fx-background-color: #0284c7; -fx-text-fill: white; -fx-font-weight: bold; -fx-background-radius: 4;");
        statementBtn.setOnAction(e -> handleViewCustomerStatement());

        actionRow.getChildren().addAll(payBtn, statementBtn);

        setupCustomerTable();
        VBox.setVgrow(customerTable, Priority.ALWAYS);

        box.getChildren().addAll(actionRow, customerTable);
        return box;
    }

    private void setupCustomerTable() {
        TableColumn<CustomerDuesSummary, String> colName = new TableColumn<>("Customer Name");
        colName.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().customerName()));
        colName.setPrefWidth(220);

        TableColumn<CustomerDuesSummary, String> colPhone = new TableColumn<>("Phone");
        colPhone.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().phone() != null ? d.getValue().phone() : "-"));
        colPhone.setPrefWidth(140);

        TableColumn<CustomerDuesSummary, String> colType = new TableColumn<>("Type");
        colType.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().customerType().name()));
        colType.setPrefWidth(120);

        TableColumn<CustomerDuesSummary, String> colDues = new TableColumn<>("Total Dues (Rs)");
        colDues.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().currentDues().toRupeesString()));
        colDues.setPrefWidth(160);
        colDues.setCellFactory(column -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setStyle("");
                } else {
                    setText(item);
                    if (!"0.00".equals(item)) {
                        setStyle("-fx-text-fill: #dc2626; -fx-font-weight: bold;");
                    }
                }
            }
        });

        TableColumn<CustomerDuesSummary, String> colLimit = new TableColumn<>("Credit Limit (Rs)");
        colLimit.setCellValueFactory(d -> new SimpleStringProperty(
                d.getValue().creditLimit() != null ? d.getValue().creditLimit().toRupeesString() : "No Limit"
        ));
        colLimit.setPrefWidth(150);

        customerTable.getColumns().addAll(colName, colPhone, colType, colDues, colLimit);
    }

    private VBox buildSupplierTab() {
        VBox box = new VBox(15);
        box.setPadding(new Insets(15, 25, 15, 25));

        HBox actionRow = new HBox(15);
        actionRow.setAlignment(Pos.CENTER_LEFT);

        Button paySuppBtn = new Button("Record Payment Made to Supplier");
        paySuppBtn.setStyle("-fx-background-color: #16a34a; -fx-text-fill: white; -fx-font-weight: bold; -fx-background-radius: 4;");
        paySuppBtn.setOnAction(e -> handleRecordSupplierPayment());

        Button suppStmtBtn = new Button("View Supplier Statement");
        suppStmtBtn.setStyle("-fx-background-color: #0284c7; -fx-text-fill: white; -fx-font-weight: bold; -fx-background-radius: 4;");
        suppStmtBtn.setOnAction(e -> handleViewSupplierStatement());

        actionRow.getChildren().addAll(paySuppBtn, suppStmtBtn);

        setupSupplierTable();
        VBox.setVgrow(supplierTable, Priority.ALWAYS);

        box.getChildren().addAll(actionRow, supplierTable);
        return box;
    }

    private void setupSupplierTable() {
        TableColumn<SupplierPayableSummary, String> colName = new TableColumn<>("Supplier Name");
        colName.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().supplierName()));
        colName.setPrefWidth(220);

        TableColumn<SupplierPayableSummary, String> colPhone = new TableColumn<>("Phone");
        colPhone.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().phone() != null ? d.getValue().phone() : "-"));
        colPhone.setPrefWidth(130);

        TableColumn<SupplierPayableSummary, String> colContact = new TableColumn<>("Contact Person");
        colContact.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().contactPerson() != null ? d.getValue().contactPerson() : "-"));
        colContact.setPrefWidth(160);

        TableColumn<SupplierPayableSummary, String> colBalance = new TableColumn<>("Outstanding Payable (Rs)");
        colBalance.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().currentPayable().toRupeesString()));
        colBalance.setPrefWidth(180);

        supplierTable.getColumns().addAll(colName, colPhone, colContact, colBalance);
    }

    private void loadCustomerData() {
        List<CustomerDuesSummary> list = context.getCreditLedgerService().getAllCustomersWithDues();
        customerDuesData.setAll(list);
    }

    private void loadSupplierData() {
        List<SupplierPayableSummary> list = context.getCreditLedgerService().getAllSuppliersWithPayables();
        supplierData.setAll(list);
    }

    private void handleRecordCustomerPayment() {
        CustomerDuesSummary selected = customerTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            new Alert(Alert.AlertType.WARNING, "Please select a customer from the table", ButtonType.OK).showAndWait();
            return;
        }

        Dialog<Boolean> dialog = new Dialog<>();
        dialog.setTitle("Record Payment from " + selected.customerName());
        dialog.setHeaderText("Outstanding Balance: " + selected.currentDues().toFormattedString());

        ButtonType confirm = new ButtonType("Save Payment", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(confirm, ButtonType.CANCEL);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20));

        TextField amountField = new TextField(selected.currentDues().toRupeesString());
        ComboBox<PaymentMode> modeCombo = new ComboBox<>(FXCollections.observableArrayList(PaymentMode.CASH, PaymentMode.UPI));
        modeCombo.setValue(PaymentMode.CASH);
        TextField notesField = new TextField("Customer payment");

        grid.add(new Label("Payment Amount (Rs):"), 0, 0);
        grid.add(amountField, 1, 0);
        grid.add(new Label("Payment Mode:"), 0, 1);
        grid.add(modeCombo, 1, 1);
        grid.add(new Label("Notes:"), 0, 2);
        grid.add(notesField, 1, 2);

        dialog.getDialogPane().setContent(grid);

        dialog.setResultConverter(btn -> {
            if (btn == confirm) {
                try {
                    Money amount = Money.parse(amountField.getText().trim());
                    PaymentMode mode = modeCombo.getValue();
                    String notes = notesField.getText();
                    long userId = context.getCurrentUser() != null ? context.getCurrentUser().id() : 1L;

                    context.getCreditLedgerService().recordCustomerPayment(
                            selected.customerId(),
                            amount,
                            mode,
                            null,
                            context.getClock().today(),
                            null,
                            notes,
                            userId
                    );

                    new Alert(Alert.AlertType.INFORMATION, "Payment recorded successfully!", ButtonType.OK).showAndWait();
                    loadCustomerData();
                    return true;
                } catch (Exception ex) {
                    new Alert(Alert.AlertType.ERROR, "Error: " + ex.getMessage(), ButtonType.OK).showAndWait();
                    return false;
                }
            }
            return null;
        });

        dialog.showAndWait();
    }

    private void handleViewCustomerStatement() {
        CustomerDuesSummary selected = customerTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            new Alert(Alert.AlertType.WARNING, "Please select a customer from the table", ButtonType.OK).showAndWait();
            return;
        }

        CustomerCreditStatement stmt = context.getCreditLedgerService().getCustomerStatement(selected.customerId(), context.getClock().today());

        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Customer Statement - " + stmt.customer().name());
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        VBox content = new VBox(10);
        content.setPrefSize(750, 500);

        Label header = new Label(String.format("Customer: %s • Phone: %s • Total Outstanding: %s",
                stmt.customer().name(), stmt.customer().phone(), stmt.currentDues().toFormattedString()));
        header.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");

        AgingSummary aging = stmt.aging();
        Label agingLabel = new Label(String.format("Aging Breakdown (FR-28): 0-30d: %s | 31-60d: %s | 61-90d: %s | 90+d: %s",
                aging.days0To30().toFormattedString(),
                aging.days31To60().toFormattedString(),
                aging.days61To90().toFormattedString(),
                aging.days90Plus().toFormattedString()));
        agingLabel.setStyle("-fx-background-color: #f1f5f9; -fx-padding: 6 10; -fx-font-weight: bold; -fx-text-fill: #334155;");

        TableView<CustomerLedgerEntry> entryTable = new TableView<>(FXCollections.observableArrayList(stmt.entries()));

        TableColumn<CustomerLedgerEntry, String> colDate = new TableColumn<>("Date");
        colDate.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().entryDate().toString()));

        TableColumn<CustomerLedgerEntry, String> colType = new TableColumn<>("Type");
        colType.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().entryType()));

        TableColumn<CustomerLedgerEntry, String> colAmount = new TableColumn<>("Amount (Rs)");
        colAmount.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().amount().toRupeesString()));

        TableColumn<CustomerLedgerEntry, String> colNotes = new TableColumn<>("Notes / Ref");
        colNotes.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().notes()));
        colNotes.setPrefWidth(250);

        entryTable.getColumns().addAll(colDate, colType, colAmount, colNotes);
        VBox.setVgrow(entryTable, Priority.ALWAYS);

        content.getChildren().addAll(header, agingLabel, entryTable);
        dialog.getDialogPane().setContent(content);
        dialog.showAndWait();
    }

    private void handleRecordSupplierPayment() {
        SupplierPayableSummary selected = supplierTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            new Alert(Alert.AlertType.WARNING, "Please select a supplier from the table", ButtonType.OK).showAndWait();
            return;
        }

        TextInputDialog dialog = new TextInputDialog(selected.currentPayable().toRupeesString());
        dialog.setTitle("Record Payment to Supplier");
        dialog.setHeaderText("Pay to " + selected.supplierName() + " (Balance: " + selected.currentPayable().toFormattedString() + ")");
        dialog.setContentText("Amount (Rs):");

        dialog.showAndWait().ifPresent(text -> {
            try {
                Money amount = Money.parse(text.trim());
                long userId = context.getCurrentUser() != null ? context.getCurrentUser().id() : 1L;
                context.getCreditLedgerService().recordSupplierPayment(
                        selected.supplierId(),
                        amount,
                        PaymentMode.CASH,
                        null,
                        context.getClock().today(),
                        "Distributor payment",
                        userId
                );
                new Alert(Alert.AlertType.INFORMATION, "Supplier payment recorded!", ButtonType.OK).showAndWait();
                loadSupplierData();
            } catch (Exception ex) {
                new Alert(Alert.AlertType.ERROR, "Failed: " + ex.getMessage(), ButtonType.OK).showAndWait();
            }
        });
    }

    private void handleViewSupplierStatement() {
        SupplierPayableSummary selected = supplierTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            new Alert(Alert.AlertType.WARNING, "Please select a supplier from the table", ButtonType.OK).showAndWait();
            return;
        }

        SupplierStatement stmt = context.getCreditLedgerService().getSupplierStatement(selected.supplierId(), context.getClock().today());

        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Supplier Statement - " + stmt.supplier().name());
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        VBox content = new VBox(10);
        content.setPrefSize(750, 500);

        Label header = new Label(String.format("Supplier: %s • Phone: %s • Total Payable: %s",
                stmt.supplier().name(), stmt.supplier().phone(), stmt.currentPayable().toFormattedString()));
        header.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");

        AgingSummary aging = stmt.aging();
        Label agingLabel = new Label(String.format("Aging Breakdown (FR-28): 0-30d: %s | 31-60d: %s | 61-90d: %s | 90+d: %s",
                aging.days0To30().toFormattedString(),
                aging.days31To60().toFormattedString(),
                aging.days61To90().toFormattedString(),
                aging.days90Plus().toFormattedString()));
        agingLabel.setStyle("-fx-background-color: #f1f5f9; -fx-padding: 6 10; -fx-font-weight: bold; -fx-text-fill: #334155;");

        TableView<SupplierLedgerEntry> entryTable = new TableView<>(FXCollections.observableArrayList(stmt.entries()));

        TableColumn<SupplierLedgerEntry, String> colDate = new TableColumn<>("Date");
        colDate.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().entryDate().toString()));

        TableColumn<SupplierLedgerEntry, String> colType = new TableColumn<>("Type");
        colType.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().entryType()));

        TableColumn<SupplierLedgerEntry, String> colAmount = new TableColumn<>("Amount (Rs)");
        colAmount.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().amount().toRupeesString()));

        TableColumn<SupplierLedgerEntry, String> colNotes = new TableColumn<>("Notes / Ref");
        colNotes.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().notes()));
        colNotes.setPrefWidth(250);

        entryTable.getColumns().addAll(colDate, colType, colAmount, colNotes);
        VBox.setVgrow(entryTable, Priority.ALWAYS);

        content.getChildren().addAll(header, agingLabel, entryTable);
        dialog.getDialogPane().setContent(content);
        dialog.showAndWait();
    }
}
