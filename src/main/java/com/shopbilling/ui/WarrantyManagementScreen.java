package com.shopbilling.ui;

import com.shopbilling.model.WarrantyRecord;
import com.shopbilling.model.WarrantyStatus;
import com.shopbilling.service.WarrantyTrackingService;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Screen providing Appliance Serial Number and Warranty Tracking lifecycle management
 * (PRD Section 11, Section 15 Open Question 6).
 */
public class WarrantyManagementScreen {

    private static final Logger logger = LoggerFactory.getLogger(WarrantyManagementScreen.class);

    private final ScreenManager screenManager;
    private final AppContext context;
    private final WarrantyTrackingService warrantyService;

    private final ObservableList<WarrantyRecord> expiringData = FXCollections.observableArrayList();
    private final TableView<WarrantyRecord> expiringTable = new TableView<>(expiringData);

    private final TextField searchField = new TextField();
    private final Label lookupResultLabel = new Label("Scan barcode or enter serial number above to check warranty status.");
    private final Button claimBtn = new Button("Process Service / Claim");

    private WarrantyRecord currentLookedUpRecord = null;

    private final Label totalCountLabel = new Label("0");
    private final Label activeCountLabel = new Label("0");
    private final Label expiringCountLabel = new Label("0");

    public WarrantyManagementScreen(ScreenManager screenManager) {
        this.screenManager = Objects.requireNonNull(screenManager, "ScreenManager cannot be null");
        this.context = screenManager.getContext();
        this.warrantyService = context.getWarrantyTrackingService();
    }

    public Parent getView() {
        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color: #f8fafc;");

        // 1. TOP BAR
        HBox topBar = new HBox(15);
        topBar.setAlignment(Pos.CENTER_LEFT);
        topBar.setPadding(new Insets(15, 25, 15, 25));
        topBar.setStyle("-fx-background-color: #ffffff; -fx-border-color: #e2e8f0; -fx-border-width: 0 0 1 0;");

        Button backBtn = new Button("← Back to Home");
        backBtn.setStyle("-fx-background-color: #f1f5f9; -fx-border-color: #cbd5e1; -fx-border-radius: 4;");
        backBtn.setOnAction(e -> screenManager.showHome());

        Label title = new Label("Appliance Serial Number & Warranty Tracking");
        title.setStyle("-fx-font-size: 20px; -fx-font-weight: bold; -fx-text-fill: #0f172a;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button registerBtn = new Button("+ Register Warranty");
        registerBtn.setStyle("-fx-background-color: #2563eb; -fx-text-fill: white; -fx-font-weight: bold; -fx-background-radius: 4;");
        registerBtn.setOnAction(e -> showRegisterWarrantyDialog());

        topBar.getChildren().addAll(backBtn, title, spacer, registerBtn);
        root.setTop(topBar);

        // 2. CENTER CONTENT
        VBox centerBox = new VBox(20);
        centerBox.setPadding(new Insets(20, 25, 20, 25));

        // KPI Summary Cards
        HBox metricsBox = new HBox(15);
        metricsBox.getChildren().addAll(
                createCard("ACTIVE WARRANTIES", activeCountLabel, "#059669"),
                createCard("EXPIRING IN 30 DAYS", expiringCountLabel, "#d97706"),
                createCard("TOTAL REGISTERED", totalCountLabel, "#2563eb")
        );

        // Serial Number Lookup Box
        VBox lookupBox = new VBox(12);
        lookupBox.setPadding(new Insets(15));
        lookupBox.setStyle("-fx-background-color: #ffffff; -fx-border-color: #e2e8f0; -fx-border-radius: 8;");

        Label lookupTitle = new Label("Quick Serial Number & Barcode Verification");
        lookupTitle.setStyle("-fx-font-weight: bold; -fx-font-size: 14px; -fx-text-fill: #1e293b;");

        HBox searchRow = new HBox(10);
        searchRow.setAlignment(Pos.CENTER_LEFT);
        searchField.setPromptText("Enter or scan serial number (e.g. SN-GEYSER-2026-001)...");
        searchField.setPrefWidth(400);

        Button checkBtn = new Button("Verify Warranty");
        checkBtn.setStyle("-fx-background-color: #0f172a; -fx-text-fill: white; -fx-font-weight: bold; -fx-background-radius: 4;");
        checkBtn.setOnAction(e -> checkSerialStatus());
        searchField.setOnAction(e -> checkSerialStatus());

        claimBtn.setStyle("-fx-background-color: #ea580c; -fx-text-fill: white; -fx-font-weight: bold; -fx-background-radius: 4;");
        claimBtn.setDisable(true);
        claimBtn.setOnAction(e -> showProcessClaimDialog());

        searchRow.getChildren().addAll(searchField, checkBtn, claimBtn);

        lookupResultLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #475569;");
        lookupBox.getChildren().addAll(lookupTitle, searchRow, lookupResultLabel);

        // Table for Expiring Soon
        VBox tableBox = new VBox(10);
        VBox.setVgrow(tableBox, Priority.ALWAYS);

        Label tableTitle = new Label("Appliance Warranties Expiring Soon (Next 30 Days)");
        tableTitle.setStyle("-fx-font-weight: bold; -fx-font-size: 14px; -fx-text-fill: #1e293b;");

        setupExpiringTable();
        VBox.setVgrow(expiringTable, Priority.ALWAYS);

        tableBox.getChildren().addAll(tableTitle, expiringTable);

        centerBox.getChildren().addAll(metricsBox, lookupBox, tableBox);
        root.setCenter(centerBox);

        loadData();
        return root;
    }

    private void setupExpiringTable() {
        TableColumn<WarrantyRecord, String> serialCol = new TableColumn<>("Serial Number");
        serialCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().serialNumber()));
        serialCol.setPrefWidth(180);

        TableColumn<WarrantyRecord, String> phoneCol = new TableColumn<>("Customer Phone");
        phoneCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().customerPhone() != null ? c.getValue().customerPhone() : "-"));
        phoneCol.setPrefWidth(140);

        TableColumn<WarrantyRecord, String> startCol = new TableColumn<>("Purchase Date");
        startCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().warrantyStartDate().toString()));
        startCol.setPrefWidth(120);

        TableColumn<WarrantyRecord, String> durationCol = new TableColumn<>("Duration");
        durationCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().warrantyMonths() + " Mos"));
        durationCol.setPrefWidth(100);

        TableColumn<WarrantyRecord, String> expiryCol = new TableColumn<>("Expiry Date");
        expiryCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().warrantyEndDate().toString()));
        expiryCol.setPrefWidth(120);

        TableColumn<WarrantyRecord, String> daysLeftCol = new TableColumn<>("Days Remaining");
        daysLeftCol.setCellValueFactory(c -> {
            LocalDate expiry = c.getValue().warrantyEndDate();
            long days = ChronoUnit.DAYS.between(context.getClock().today(), expiry);
            return new SimpleStringProperty(days >= 0 ? days + " days" : "Expired");
        });
        daysLeftCol.setPrefWidth(130);

        TableColumn<WarrantyRecord, String> statusCol = new TableColumn<>("Status");
        statusCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().status().name()));
        statusCol.setPrefWidth(110);

        expiringTable.getColumns().addAll(List.of(serialCol, phoneCol, startCol, durationCol, expiryCol, daysLeftCol, statusCol));
    }

    private void loadData() {
        try {
            List<WarrantyRecord> expiring = warrantyService.getWarrantiesExpiringWithin(30);
            expiringData.setAll(expiring);

            expiringCountLabel.setText(String.valueOf(expiring.size()));
            activeCountLabel.setText(String.valueOf(expiring.stream().filter(w -> w.status() == WarrantyStatus.ACTIVE).count()));
            totalCountLabel.setText(String.valueOf(expiring.size()));
        } catch (Exception e) {
            logger.error("Failed to load warranty data", e);
        }
    }

    private void checkSerialStatus() {
        String serial = searchField.getText().trim();
        if (serial.isEmpty()) {
            lookupResultLabel.setText("Please enter a valid serial number.");
            claimBtn.setDisable(true);
            currentLookedUpRecord = null;
            return;
        }

        Optional<WarrantyRecord> opt = warrantyService.lookupWarranty(serial);
        if (opt.isEmpty()) {
            lookupResultLabel.setText("❌ No appliance registered with serial number: " + serial);
            lookupResultLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #dc2626; -fx-font-weight: bold;");
            claimBtn.setDisable(true);
            currentLookedUpRecord = null;
        } else {
            WarrantyRecord rec = opt.get();
            currentLookedUpRecord = rec;
            LocalDate expiry = rec.warrantyEndDate();
            boolean active = rec.status() == WarrantyStatus.ACTIVE && !context.getClock().today().isAfter(expiry);

            String statusColor = active ? "#059669" : (rec.status() == WarrantyStatus.CLAIMED ? "#ea580c" : "#dc2626");
            String info = String.format("✔ Serial: %s | Status: %s | Expiry: %s | Customer: %s (%s) | Notes: %s",
                    rec.serialNumber(), rec.status(), rec.warrantyEndDate(),
                    rec.customerName() != null ? rec.customerName() : "Counter Sale",
                    rec.customerPhone() != null ? rec.customerPhone() : "N/A",
                    rec.claimNotes() != null ? rec.claimNotes() : "None");

            lookupResultLabel.setText(info);
            lookupResultLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: " + statusColor + "; -fx-font-weight: bold;");
            claimBtn.setDisable(!active);
        }
    }

    private void showProcessClaimDialog() {
        if (currentLookedUpRecord == null) return;

        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("Process Warranty Claim / Service");
        dialog.setHeaderText("Register Service Claim for Serial: " + currentLookedUpRecord.serialNumber());
        dialog.setContentText("Enter claim description / repair notes:");

        dialog.showAndWait().ifPresent(notes -> {
            if (!notes.trim().isEmpty()) {
                try {
                    long userId = context.getCurrentUser() != null ? context.getCurrentUser().id() : 1L;
                    warrantyService.processWarrantyClaim(currentLookedUpRecord.serialNumber(), WarrantyStatus.CLAIMED, notes.trim(), userId);
                    checkSerialStatus();
                    loadData();
                    Alert alert = new Alert(Alert.AlertType.INFORMATION, "Warranty claim registered successfully.");
                    alert.showAndWait();
                } catch (Exception ex) {
                    Alert alert = new Alert(Alert.AlertType.ERROR, "Failed to process claim: " + ex.getMessage());
                    alert.showAndWait();
                }
            }
        });
    }

    private void showRegisterWarrantyDialog() {
        Dialog<WarrantyRecord> dialog = new Dialog<>();
        dialog.setTitle("Register Appliance Serial & Warranty");
        dialog.setHeaderText("Enter appliance sale details to register serial warranty");

        ButtonType saveBtnType = new ButtonType("Register", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveBtnType, ButtonType.CANCEL);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20, 150, 10, 10));

        TextField saleIdField = new TextField("1");
        TextField variantField = new TextField("1");
        TextField serialField = new TextField();
        serialField.setPromptText("e.g. SN-GEYSER-1001");
        TextField durationField = new TextField("24");

        grid.add(new Label("Sale ID:"), 0, 0);
        grid.add(saleIdField, 1, 0);
        grid.add(new Label("Variant ID:"), 0, 1);
        grid.add(variantField, 1, 1);
        grid.add(new Label("Serial Number:"), 0, 2);
        grid.add(serialField, 1, 2);
        grid.add(new Label("Warranty Months:"), 0, 3);
        grid.add(durationField, 1, 3);

        dialog.getDialogPane().setContent(grid);

        dialog.setResultConverter(dialogButton -> {
            if (dialogButton == saveBtnType) {
                try {
                    long saleId = Long.parseLong(saleIdField.getText().trim());
                    long variantId = Long.parseLong(variantField.getText().trim());
                    String serial = serialField.getText().trim();
                    int months = Integer.parseInt(durationField.getText().trim());
                    long userId = context.getCurrentUser() != null ? context.getCurrentUser().id() : 1L;

                    return warrantyService.registerWarranty(saleId, variantId, serial, months, userId);
                } catch (Exception ex) {
                    Alert alert = new Alert(Alert.AlertType.ERROR, "Registration failed: " + ex.getMessage());
                    alert.showAndWait();
                }
            }
            return null;
        });

        dialog.showAndWait().ifPresent(rec -> loadData());
    }

    private VBox createCard(String title, Label valLabel, String color) {
        VBox card = new VBox(6);
        card.setPadding(new Insets(12, 20, 12, 20));
        card.setPrefWidth(220);
        card.setStyle("-fx-background-color: #ffffff; -fx-border-color: #e2e8f0; -fx-border-radius: 8;");

        Label t = new Label(title);
        t.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: #64748b;");

        valLabel.setStyle("-fx-font-size: 22px; -fx-font-weight: bold; -fx-text-fill: " + color + ";");

        card.getChildren().addAll(t, valLabel);
        return card;
    }
}
