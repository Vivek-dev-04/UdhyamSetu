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
import java.util.Objects;

/**
 * Screen providing sales reports, period aggregations, CSV export,
 * and owner-only profit analysis using weighted average cost (PRD Section 7.8, FR-33 - FR-37).
 */
public class ReportsAndProfitScreen {

    private final ScreenManager screenManager;
    private final AppContext context;

    private final ObservableList<ProductProfitRow> profitData = FXCollections.observableArrayList();
    private final TableView<ProductProfitRow> profitTable = new TableView<>(profitData);

    private final Label revLabel = new Label("₹0.00");
    private final Label cogsLabel = new Label("₹0.00");
    private final Label grossProfitLabel = new Label("₹0.00");
    private final Label expenseLabel = new Label("₹0.00");
    private final Label netProfitLabel = new Label("₹0.00");
    private final Label netMarginLabel = new Label("0.0%");

    public ReportsAndProfitScreen(ScreenManager screenManager) {
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

        Label title = new Label("Reports & Profit Analysis");
        title.setStyle("-fx-font-size: 20px; -fx-font-weight: bold; -fx-text-fill: #0f172a;");

        topBar.getChildren().addAll(backBtn, title);
        root.setTop(topBar);

        TabPane tabPane = new TabPane();
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        Tab periodTab = new Tab("Periodic Sales Reports", buildPeriodicSalesTab());
        Tab profitTab = new Tab("Owner Profit View (FR-37)", buildProfitTab());
        if (!context.isOwner()) {
            profitTab.setDisable(true);
        }

        tabPane.getTabs().addAll(periodTab, profitTab);
        root.setCenter(tabPane);

        return root;
    }

    private VBox buildPeriodicSalesTab() {
        VBox box = new VBox(15);
        box.setPadding(new Insets(15, 25, 15, 25));

        FinancialYear fy = FinancialYear.ofDate(context.getClock().today());

        HBox periodControl = new HBox(15);
        periodControl.setAlignment(Pos.CENTER_LEFT);

        ComboBox<String> periodCombo = new ComboBox<>(FXCollections.observableArrayList(
                "Current Quarter (" + fy.getQuarterOf(context.getClock().today()).name() + ")",
                "Full Year (" + fy.getLabel() + ")",
                "Q1 (Apr - Jun)",
                "Q2 (Jul - Sep)",
                "Q3 (Oct - Dec)",
                "Q4 (Jan - Mar)"
        ));
        periodCombo.setValue("Current Quarter (" + fy.getQuarterOf(context.getClock().today()).name() + ")");

        Label grossLbl = new Label("₹0.00");
        grossLbl.setStyle("-fx-font-weight: bold; -fx-font-size: 16px; -fx-text-fill: #1e293b;");
        Label netLbl = new Label("₹0.00");
        netLbl.setStyle("-fx-font-weight: bold; -fx-font-size: 16px; -fx-text-fill: #059669;");
        Label countLbl = new Label("0 txns");

        Button exportBtn = new Button("Export CSV");
        exportBtn.setStyle("-fx-background-color: #0284c7; -fx-text-fill: white; -fx-font-weight: bold; -fx-background-radius: 4;");

        Runnable updatePeriodic = () -> {
            String val = periodCombo.getValue();
            SalesReportResult rep;
            if (val.contains("Full Year")) {
                rep = context.getReportService().generateYearlyReport(fy);
            } else if (val.contains("Q1")) {
                rep = context.getReportService().generateQuarterlyReport(fy, Quarter.Q1);
            } else if (val.contains("Q2")) {
                rep = context.getReportService().generateQuarterlyReport(fy, Quarter.Q2);
            } else if (val.contains("Q3")) {
                rep = context.getReportService().generateQuarterlyReport(fy, Quarter.Q3);
            } else if (val.contains("Q4")) {
                rep = context.getReportService().generateQuarterlyReport(fy, Quarter.Q4);
            } else {
                Quarter q = fy.getQuarterOf(context.getClock().today());
                rep = context.getReportService().generateQuarterlyReport(fy, q);
            }

            grossLbl.setText(rep.grossSales().toFormattedString());
            netLbl.setText(rep.netSales().toFormattedString());
            countLbl.setText(rep.totalTransactions() + " txns (" + rep.billedTransactions() + " bills, " + rep.registeredTransactions() + " reg)");

            exportBtn.setOnAction(e -> {
                FileChooser fc = new FileChooser();
                fc.setTitle("Save Period Sales CSV");
                fc.setInitialFileName("sales_period.csv");
                File f = fc.showSaveDialog(screenManager.getStage());
                if (f != null) {
                    try (FileWriter fw = new FileWriter(f)) {
                        context.getReportService().exportToCsv(rep, fw);
                        new Alert(Alert.AlertType.INFORMATION, "Exported successfully!", ButtonType.OK).showAndWait();
                    } catch (Exception ex) {
                        new Alert(Alert.AlertType.ERROR, "Export error: " + ex.getMessage(), ButtonType.OK).showAndWait();
                    }
                }
            });
        };

        periodCombo.setOnAction(e -> updatePeriodic.run());

        periodControl.getChildren().addAll(
                new Label("Select Financial Period:"), periodCombo,
                new Separator(javafx.geometry.Orientation.VERTICAL),
                new Label("Gross:"), grossLbl,
                new Label("Net Sales:"), netLbl,
                countLbl,
                exportBtn
        );

        box.getChildren().add(periodControl);
        updatePeriodic.run();
        return box;
    }

    private VBox buildProfitTab() {
        VBox box = new VBox(15);
        box.setPadding(new Insets(15, 25, 15, 25));

        // Period filter & documentation button
        HBox filterRow = new HBox(15);
        filterRow.setAlignment(Pos.CENTER_LEFT);

        DatePicker fromPicker = new DatePicker(context.getClock().today().withDayOfMonth(1));
        DatePicker toPicker = new DatePicker(context.getClock().today());

        Button calculateBtn = new Button("Calculate Profit");
        calculateBtn.setStyle("-fx-background-color: #2563eb; -fx-text-fill: white; -fx-font-weight: bold; -fx-background-radius: 4;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button docBtn = new Button("Costing Method Documentation (FR-37)");
        docBtn.setStyle("-fx-background-color: #f1f5f9; -fx-border-color: #cbd5e1; -fx-border-radius: 4;");
        docBtn.setOnAction(e -> {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle("Costing Method Documentation");
            alert.setHeaderText("Weighted Average Purchase Costing (PRD FR-37)");
            alert.setContentText(context.getProfitService().getCostingMethodDocumentation());
            alert.showAndWait();
        });

        filterRow.getChildren().addAll(new Label("From:"), fromPicker, new Label("To:"), toPicker, calculateBtn, spacer, docBtn);

        // Summary Ribbon
        HBox ribbon = new HBox(20);
        ribbon.setPadding(new Insets(12, 20, 12, 20));
        ribbon.setStyle("-fx-background-color: #ffffff; -fx-border-color: #e2e8f0; -fx-border-radius: 6;");
        ribbon.setAlignment(Pos.CENTER_LEFT);

        revLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 15px; -fx-text-fill: #1e293b;");
        cogsLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 15px; -fx-text-fill: #64748b;");
        grossProfitLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 15px; -fx-text-fill: #0284c7;");
        expenseLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 15px; -fx-text-fill: #dc2626;");
        netProfitLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 17px; -fx-text-fill: #059669;");
        netMarginLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 17px; -fx-text-fill: #059669;");

        ribbon.getChildren().addAll(
                new VBox(2, new Label("Net Revenue"), revLabel),
                new Separator(javafx.geometry.Orientation.VERTICAL),
                new VBox(2, new Label("COGS (Cost)"), cogsLabel),
                new Separator(javafx.geometry.Orientation.VERTICAL),
                new VBox(2, new Label("Gross Margin"), grossProfitLabel),
                new Separator(javafx.geometry.Orientation.VERTICAL),
                new VBox(2, new Label("Operating Expenses"), expenseLabel),
                new Separator(javafx.geometry.Orientation.VERTICAL),
                new VBox(2, new Label("Net Profit"), netProfitLabel),
                new Separator(javafx.geometry.Orientation.VERTICAL),
                new VBox(2, new Label("Net Margin %"), netMarginLabel)
        );

        setupProfitTable();
        VBox.setVgrow(profitTable, Priority.ALWAYS);

        Runnable refreshProfit = () -> {
            try {
                long userId = context.getCurrentUser() != null ? context.getCurrentUser().id() : 1L;
                ProfitReportResult res = context.getProfitService().generateProfitReport(userId, fromPicker.getValue(), toPicker.getValue());
                profitData.setAll(res.productProfits());

                revLabel.setText(res.totalRevenue().toFormattedString());
                cogsLabel.setText(res.totalCogs().toFormattedString());
                grossProfitLabel.setText(res.grossProfit().toFormattedString());
                expenseLabel.setText(res.totalExpenses().toFormattedString());
                netProfitLabel.setText(res.netProfit().toFormattedString());
                netMarginLabel.setText(String.format("%.1f%%", res.netMarginPercent()));

            } catch (Exception ex) {
                new Alert(Alert.AlertType.ERROR, "Failed to calculate profit: " + ex.getMessage(), ButtonType.OK).showAndWait();
            }
        };

        calculateBtn.setOnAction(e -> refreshProfit.run());

        box.getChildren().addAll(filterRow, ribbon, profitTable);
        refreshProfit.run();
        return box;
    }

    private void setupProfitTable() {
        TableColumn<ProductProfitRow, String> colProd = new TableColumn<>("Product & Variant");
        colProd.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().productName() + " (" + d.getValue().variantName() + ")"));
        colProd.setPrefWidth(240);

        TableColumn<ProductProfitRow, String> colBrand = new TableColumn<>("Brand");
        colBrand.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().brandName()));
        colBrand.setPrefWidth(120);

        TableColumn<ProductProfitRow, String> colQty = new TableColumn<>("Net Sold Qty");
        colQty.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().quantitySold().toDisplayString()));
        colQty.setPrefWidth(100);

        TableColumn<ProductProfitRow, String> colRev = new TableColumn<>("Revenue (Rs)");
        colRev.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().revenue().toRupeesString()));
        colRev.setPrefWidth(110);

        TableColumn<ProductProfitRow, String> colUnitCost = new TableColumn<>("Weighted Unit Cost");
        colUnitCost.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().weightedAvgUnitCost().toRupeesString()));
        colUnitCost.setPrefWidth(130);

        TableColumn<ProductProfitRow, String> colCogs = new TableColumn<>("COGS (Rs)");
        colCogs.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().cogs().toRupeesString()));
        colCogs.setPrefWidth(110);

        TableColumn<ProductProfitRow, String> colMargin = new TableColumn<>("Gross Margin (Rs)");
        colMargin.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().grossMargin().toRupeesString()));
        colMargin.setPrefWidth(130);

        TableColumn<ProductProfitRow, String> colMarginPct = new TableColumn<>("Margin %");
        colMarginPct.setCellValueFactory(d -> new SimpleStringProperty(String.format("%.1f%%", d.getValue().marginPercent())));
        colMarginPct.setPrefWidth(90);

        profitTable.getColumns().addAll(colProd, colBrand, colQty, colRev, colUnitCost, colCogs, colMargin, colMarginPct);
    }
}
