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

import java.time.LocalDate;
import java.util.*;

/**
 * Screen managing expenses and owner-only day-end cash closing with auto-backup
 * (PRD Section 7.7, 7.9, FR-31, FR-32, FR-38, FR-39, FR-40).
 */
public class ExpenseAndClosingScreen {

    private final ScreenManager screenManager;
    private final AppContext context;

    private final ObservableList<ExpenseRecord> expenseData = FXCollections.observableArrayList();
    private final TableView<ExpenseRecord> expenseTable = new TableView<>(expenseData);

    public ExpenseAndClosingScreen(ScreenManager screenManager) {
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

        Label title = new Label("Expenses & Day-End Cash Closing");
        title.setStyle("-fx-font-size: 20px; -fx-font-weight: bold; -fx-text-fill: #0f172a;");

        topBar.getChildren().addAll(backBtn, title);
        root.setTop(topBar);

        TabPane tabPane = new TabPane();
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        Tab expenseTab = new Tab("Daily Expenses", buildExpenseTab());
        Tab closingTab = new Tab("Day-End Cash Closing (Owner)", buildClosingTab());
        if (!context.isOwner()) {
            closingTab.setDisable(true);
        }

        tabPane.getTabs().addAll(expenseTab, closingTab);
        root.setCenter(tabPane);

        loadExpenses();
        return root;
    }

    private VBox buildExpenseTab() {
        VBox box = new VBox(15);
        box.setPadding(new Insets(15, 25, 15, 25));

        // Entry Form
        GridPane form = new GridPane();
        form.setHgap(10);
        form.setVgap(10);
        form.setPadding(new Insets(15));
        form.setStyle("-fx-background-color: #ffffff; -fx-border-color: #e2e8f0; -fx-border-radius: 6;");

        List<ExpenseCategory> categories = context.getExpenseService().getAllCategories();
        ComboBox<ExpenseCategory> categoryCombo = new ComboBox<>(FXCollections.observableArrayList(categories));
        categoryCombo.setCellFactory(p -> new ListCell<>() {
            @Override
            protected void updateItem(ExpenseCategory c, boolean empty) {
                super.updateItem(c, empty);
                setText(empty || c == null ? null : c.name());
            }
        });
        categoryCombo.setButtonCell(categoryCombo.getCellFactory().call(null));
        if (!categories.isEmpty()) categoryCombo.setValue(categories.get(0));

        TextField amountField = new TextField();
        amountField.setPromptText("e.g. 50.00");

        ComboBox<PaymentMode> modeCombo = new ComboBox<>(FXCollections.observableArrayList(PaymentMode.CASH, PaymentMode.UPI));
        modeCombo.setValue(PaymentMode.CASH);

        TextField noteField = new TextField();
        noteField.setPromptText("Optional note");
        noteField.setPrefWidth(220);

        Button saveExpenseBtn = new Button("Add Expense");
        saveExpenseBtn.setStyle("-fx-background-color: #2563eb; -fx-text-fill: white; -fx-font-weight: bold; -fx-background-radius: 4;");
        saveExpenseBtn.setOnAction(e -> {
            try {
                ExpenseCategory cat = categoryCombo.getValue();
                Money amount = Money.parse(amountField.getText().trim());
                PaymentMode mode = modeCombo.getValue();
                String note = noteField.getText();
                long userId = context.getCurrentUser() != null ? context.getCurrentUser().id() : 1L;

                context.getExpenseService().recordExpense(
                        cat.id(),
                        amount,
                        mode,
                        context.getClock().today(),
                        note,
                        userId
                );

                amountField.clear();
                noteField.clear();
                loadExpenses();
            } catch (Exception ex) {
                new Alert(Alert.AlertType.ERROR, "Failed to save expense: " + ex.getMessage(), ButtonType.OK).showAndWait();
            }
        });

        form.add(new Label("Category:"), 0, 0);
        form.add(categoryCombo, 0, 1);
        form.add(new Label("Amount (Rs):"), 1, 0);
        form.add(amountField, 1, 1);
        form.add(new Label("Payment Mode:"), 2, 0);
        form.add(modeCombo, 2, 1);
        form.add(new Label("Note:"), 3, 0);
        form.add(noteField, 3, 1);
        form.add(new Label("Action:"), 4, 0);
        form.add(saveExpenseBtn, 4, 1);

        // Expense Table
        setupExpenseTable();
        VBox.setVgrow(expenseTable, Priority.ALWAYS);

        box.getChildren().addAll(form, expenseTable);
        return box;
    }

    private void setupExpenseTable() {
        TableColumn<ExpenseRecord, String> colDate = new TableColumn<>("Date");
        colDate.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().expenseDate().toString()));
        colDate.setPrefWidth(110);

        TableColumn<ExpenseRecord, String> colCat = new TableColumn<>("Category");
        colCat.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().categoryName()));
        colCat.setPrefWidth(160);

        TableColumn<ExpenseRecord, String> colAmount = new TableColumn<>("Amount (Rs)");
        colAmount.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().amount().toRupeesString()));
        colAmount.setPrefWidth(120);

        TableColumn<ExpenseRecord, String> colMode = new TableColumn<>("Mode");
        colMode.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().paymentMode().name()));
        colMode.setPrefWidth(100);

        TableColumn<ExpenseRecord, String> colNote = new TableColumn<>("Note");
        colNote.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().note() != null ? d.getValue().note() : ""));
        colNote.setPrefWidth(260);

        expenseTable.getColumns().addAll(colDate, colCat, colAmount, colMode, colNote);
    }

    private void loadExpenses() {
        LocalDate today = context.getClock().today();
        List<ExpenseRecord> list = context.getExpenseService().getExpensesForDateRange(today.minusDays(30), today);
        expenseData.setAll(list);
    }

    private VBox buildClosingTab() {
        VBox box = new VBox(20);
        box.setPadding(new Insets(25));
        box.setAlignment(Pos.TOP_LEFT);

        Label formulaNotice = new Label("FR-38 Rule: Expected Cash = Cash Sales - Cash Refunds + Cash Received - Cash Expenses");
        formulaNotice.setStyle("-fx-font-weight: bold; -fx-text-fill: #475569; -fx-background-color: #f1f5f9; -fx-padding: 8 12; -fx-background-radius: 4;");

        DatePicker closingDatePicker = new DatePicker(context.getClock().today());

        GridPane breakdownGrid = new GridPane();
        breakdownGrid.setHgap(20);
        breakdownGrid.setVgap(10);
        breakdownGrid.setPadding(new Insets(15));
        breakdownGrid.setStyle("-fx-background-color: #ffffff; -fx-border-color: #e2e8f0; -fx-border-radius: 6;");

        Label cashSalesLbl = new Label("₹0.00");
        Label cashRefundsLbl = new Label("₹0.00");
        Label cashPaymentsLbl = new Label("₹0.00");
        Label cashExpensesLbl = new Label("₹0.00");
        Label expectedCashLbl = new Label("₹0.00");
        expectedCashLbl.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: #1e293b;");

        TextField countedCashField = new TextField("0.00");
        countedCashField.setPrefWidth(140);
        countedCashField.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");

        Label varianceLbl = new Label("₹0.00");
        varianceLbl.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");

        TextField closingNotesField = new TextField();
        closingNotesField.setPromptText("Optional closing note / variance explanation");
        closingNotesField.setPrefWidth(300);

        Runnable refreshBreakdown = () -> {
            LocalDate date = closingDatePicker.getValue() != null ? closingDatePicker.getValue() : context.getClock().today();
            DayEndCashSummary summary = context.getDayEndClosingService().calculateDailyCashSummary(date);
            cashSalesLbl.setText(summary.cashSales().toFormattedString());
            cashRefundsLbl.setText(summary.cashRefunds().toFormattedString());
            cashPaymentsLbl.setText(summary.cashCustomerPayments().toFormattedString());
            cashExpensesLbl.setText(summary.cashExpenses().toFormattedString());
            expectedCashLbl.setText(summary.expectedCash().toFormattedString());

            try {
                Money counted = Money.parse(countedCashField.getText().trim());
                Money diff = counted.minus(summary.expectedCash());
                varianceLbl.setText(diff.toFormattedString());
                if (diff.isZero()) {
                    varianceLbl.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #16a34a;");
                } else if (diff.isNegative()) {
                    varianceLbl.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #dc2626;");
                } else {
                    varianceLbl.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #2563eb;");
                }
            } catch (Exception ignored) {}
        };

        closingDatePicker.setOnAction(e -> refreshBreakdown.run());
        countedCashField.textProperty().addListener((obs, old, val) -> refreshBreakdown.run());

        breakdownGrid.add(new Label("1. Cash Sales (+):"), 0, 0);
        breakdownGrid.add(cashSalesLbl, 1, 0);
        breakdownGrid.add(new Label("2. Cash Refunds (-):"), 0, 1);
        breakdownGrid.add(cashRefundsLbl, 1, 1);
        breakdownGrid.add(new Label("3. Cash Payments Received (+):"), 0, 2);
        breakdownGrid.add(cashPaymentsLbl, 1, 2);
        breakdownGrid.add(new Label("4. Cash Expenses (-):"), 0, 3);
        breakdownGrid.add(cashExpensesLbl, 1, 3);
        breakdownGrid.add(new Separator(), 0, 4, 2, 1);
        breakdownGrid.add(new Label("Expected Cash in Drawer:"), 0, 5);
        breakdownGrid.add(expectedCashLbl, 1, 5);
        breakdownGrid.add(new Label("Actual Counted Cash:"), 0, 6);
        breakdownGrid.add(countedCashField, 1, 6);
        breakdownGrid.add(new Label("Variance (Difference):"), 0, 7);
        breakdownGrid.add(varianceLbl, 1, 7);
        breakdownGrid.add(new Label("Notes:"), 0, 8);
        breakdownGrid.add(closingNotesField, 1, 8);

        Button closeDayBtn = new Button("Complete Day-End Close & Trigger Backup");
        closeDayBtn.setStyle("-fx-background-color: #059669; -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 14px; -fx-padding: 10 20; -fx-background-radius: 6;");
        closeDayBtn.setOnAction(e -> {
            try {
                LocalDate date = closingDatePicker.getValue() != null ? closingDatePicker.getValue() : context.getClock().today();
                Money counted = Money.parse(countedCashField.getText().trim());
                long userId = context.getCurrentUser() != null ? context.getCurrentUser().id() : 1L;

                DayEndClosingRecord record = context.getDayEndClosingService().closeDay(
                        date,
                        counted,
                        closingNotesField.getText(),
                        userId
                );

                // FR-40: Closing a day triggers a backup
                BackupRecord backup = context.getBackupService().performBackup(BackupType.DAY_END, null);

                Alert alert = new Alert(Alert.AlertType.INFORMATION);
                alert.setTitle("Day Closed Successfully");
                alert.setHeaderText("Day-end closing completed for " + record.closingDate());
                alert.setContentText(String.format("Expected: %s\nCounted: %s\nDifference: %s\n\nAutomatic Day-End Backup: %s",
                        record.expectedCash().toFormattedString(),
                        record.countedCash().toFormattedString(),
                        record.difference().toFormattedString(),
                        backup.backupFilePath()
                ));
                alert.showAndWait();

            } catch (Exception ex) {
                new Alert(Alert.AlertType.ERROR, "Failed to close day: " + ex.getMessage(), ButtonType.OK).showAndWait();
            }
        });

        box.getChildren().addAll(formulaNotice, new HBox(10, new Label("Select Closing Date:"), closingDatePicker), breakdownGrid, closeDayBtn);
        refreshBreakdown.run();
        return box;
    }
}
