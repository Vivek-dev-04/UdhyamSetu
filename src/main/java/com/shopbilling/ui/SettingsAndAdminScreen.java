package com.shopbilling.ui;

import com.shopbilling.model.*;
import com.shopbilling.service.ShopSettingsService;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;

import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Screen providing shop settings, user account administration, audit logs,
 * and database backup/restore operations (PRD Section 5, 7.10, 7.11, FR-41 - FR-50).
 */
public class SettingsAndAdminScreen {

    private final ScreenManager screenManager;
    private final AppContext context;

    private final ObservableList<User> userData = FXCollections.observableArrayList();
    private final TableView<User> userTable = new TableView<>(userData);

    private final ObservableList<AuditLogRecord> auditData = FXCollections.observableArrayList();
    private final TableView<AuditLogRecord> auditTable = new TableView<>(auditData);

    private final ObservableList<BackupRecord> backupData = FXCollections.observableArrayList();
    private final TableView<BackupRecord> backupTable = new TableView<>(backupData);

    public SettingsAndAdminScreen(ScreenManager screenManager) {
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

        Label title = new Label("Settings & System Administration");
        title.setStyle("-fx-font-size: 20px; -fx-font-weight: bold; -fx-text-fill: #0f172a;");

        topBar.getChildren().addAll(backBtn, title);
        root.setTop(topBar);

        TabPane tabPane = new TabPane();
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        Tab settingsTab = new Tab("Shop & Billing Profile", buildSettingsTab());
        Tab usersTab = new Tab("User Accounts", buildUsersTab());
        Tab auditTab = new Tab("Audit Trail (FR-44)", buildAuditTab());
        Tab backupTab = new Tab("Backups & Diagnostics", buildBackupTab());
        Tab watchdogTab = new Tab("Fraud Watchdog & Anomalies (FR-44)", buildWatchdogTab());

        if (!context.isOwner()) {
            usersTab.setDisable(true);
            auditTab.setDisable(true);
            watchdogTab.setDisable(true);
        }

        tabPane.getTabs().addAll(settingsTab, usersTab, auditTab, backupTab, watchdogTab);
        root.setCenter(tabPane);

        return root;
    }

    private ScrollPane buildSettingsTab() {
        VBox box = new VBox(20);
        box.setPadding(new Insets(20, 25, 25, 25));

        // Header info banner
        VBox headerBanner = new VBox(4);
        headerBanner.setPadding(new Insets(12, 16, 12, 16));
        headerBanner.setStyle("-fx-background-color: #eff6ff; -fx-background-radius: 8; -fx-border-color: #bfdbfe; -fx-border-radius: 8;");
        Label bannerTitle = new Label("🏢 Shop & Firm Billing Profile");
        bannerTitle.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #1e3a8a;");
        Label bannerDesc = new Label("Set your firm name, GSTIN, registered address, bank account, and UPI ID. These details are printed on tax invoices, bill PDFs, and the 'Scan to pay' QR code.");
        bannerDesc.setStyle("-fx-font-size: 13px; -fx-text-fill: #3b82f6;");
        headerBanner.getChildren().addAll(bannerTitle, bannerDesc);

        // Fetch current data
        ShopProfile currentProfile = context.getShopSettingsService().getShopProfile();
        ShopSettingsService.ShopBillingDetails currentBilling = context.getShopSettingsService().getShopBillingDetails();

        // -------------------------------------------------------------
        // CARD 1: FIRM & INVOICE DETAILS
        // -------------------------------------------------------------
        VBox card1 = new VBox(12);
        card1.setPadding(new Insets(16));
        card1.setStyle("-fx-background-color: #ffffff; -fx-border-color: #e2e8f0; -fx-border-radius: 8; -fx-background-radius: 8;");

        Label card1Title = new Label("1. Firm / Business Information (Printed on Bills & Invoices)");
        card1Title.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #0f172a;");

        GridPane grid1 = new GridPane();
        grid1.setHgap(15);
        grid1.setVgap(12);

        TextField shopNameField = new TextField(currentBilling.shopName());
        shopNameField.setPrefWidth(350);

        TextField shopTaglineField = new TextField(currentBilling.shopTagline());
        shopTaglineField.setPromptText("e.g. Dealers in Cables, Switches, Lighting & Industrial Electrical Goods");

        TextField shopAddressField = new TextField(currentBilling.shopAddress());
        shopAddressField.setPromptText("Complete Shop / Registered Office Address");

        TextField shopPhoneField = new TextField(currentBilling.shopPhone());
        shopPhoneField.setPromptText("e.g. 0141-2366620, MOB- 9829053822");

        TextField shopEmailField = new TextField(currentBilling.shopEmail());
        shopEmailField.setPromptText("e.g. Mecombines@rediffmail.Com");

        TextField shopGstinField = new TextField(currentBilling.shopGstin());
        shopGstinField.setPromptText("15-digit GSTIN (e.g. 08AACFM6417B1ZN)");

        ComboBox<String> stateCombo = new ComboBox<>();
        StateCodeHelper.getAllStates().forEach((code, state) -> stateCombo.getItems().add(state + " (" + code + ")"));
        String currState = currentBilling.shopStateName() + " (" + currentBilling.shopStateCode() + ")";
        if (stateCombo.getItems().contains(currState)) {
            stateCombo.setValue(currState);
        } else {
            stateCombo.setValue("Rajasthan (08)");
        }

        TextField shopPanField = new TextField(currentBilling.shopPan());
        shopPanField.setPromptText("10-digit PAN (e.g. AACFM6417B)");

        // Auto-extract state and PAN when GSTIN is typed
        shopGstinField.textProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) {
                String clean = newVal.trim().toUpperCase();
                if (!clean.equals(newVal)) {
                    shopGstinField.setText(clean);
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
                    shopPanField.setText(clean.substring(2, 12));
                }
            }
        });

        int r1 = 0;
        grid1.add(new Label("Firm / Shop Name: *"), 0, r1);
        grid1.add(shopNameField, 1, r1++);

        grid1.add(new Label("Tagline / Nature:"), 0, r1);
        grid1.add(shopTaglineField, 1, r1++);

        grid1.add(new Label("Shop Address:"), 0, r1);
        grid1.add(shopAddressField, 1, r1++);

        grid1.add(new Label("Phone / Contact:"), 0, r1);
        grid1.add(shopPhoneField, 1, r1++);

        grid1.add(new Label("Email Address:"), 0, r1);
        grid1.add(shopEmailField, 1, r1++);

        grid1.add(new Label("Shop GSTIN:"), 0, r1);
        grid1.add(shopGstinField, 1, r1++);

        grid1.add(new Label("State Name & Code:"), 0, r1);
        grid1.add(stateCombo, 1, r1++);

        grid1.add(new Label("PAN Number:"), 0, r1);
        grid1.add(shopPanField, 1, r1++);

        card1.getChildren().addAll(card1Title, grid1);

        // -------------------------------------------------------------
        // CARD 2: BANK & UPI DETAILS (QR CODE & BANK BOX)
        // -------------------------------------------------------------
        VBox card2 = new VBox(12);
        card2.setPadding(new Insets(16));
        card2.setStyle("-fx-background-color: #ffffff; -fx-border-color: #e2e8f0; -fx-border-radius: 8; -fx-background-radius: 8;");

        Label card2Title = new Label("2. Bank & Payment Details (Printed on Bills & 'Scan to pay' QR Code)");
        card2Title.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #0f172a;");

        GridPane grid2 = new GridPane();
        grid2.setHgap(15);
        grid2.setVgap(12);

        TextField shopBankNameField = new TextField(currentBilling.shopBankName());
        shopBankNameField.setPromptText("e.g. PUNJAB NATIONAL BANK");
        shopBankNameField.setPrefWidth(350);

        TextField shopAccountNoField = new TextField(currentBilling.shopAccountNo());
        shopAccountNoField.setPromptText("e.g. 00615010000080");

        TextField shopIfscField = new TextField(currentBilling.shopIfsc());
        shopIfscField.setPromptText("e.g. PUNB0006110");
        shopIfscField.textProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && !newVal.equals(newVal.toUpperCase())) {
                shopIfscField.setText(newVal.toUpperCase());
            }
        });

        TextField shopBranchField = new TextField(currentBilling.shopBranch());
        shopBranchField.setPromptText("e.g. M.I. ROAD - JAIPUR");

        TextField shopUpiIdField = new TextField(currentBilling.shopUpiId());
        shopUpiIdField.setPromptText("e.g. 9829053822@upi (Used to generate Bill QR Code)");

        int r2 = 0;
        grid2.add(new Label("Bank Name:"), 0, r2);
        grid2.add(shopBankNameField, 1, r2++);

        grid2.add(new Label("Account Number:"), 0, r2);
        grid2.add(shopAccountNoField, 1, r2++);

        grid2.add(new Label("IFSC Code:"), 0, r2);
        grid2.add(shopIfscField, 1, r2++);

        grid2.add(new Label("Branch Name:"), 0, r2);
        grid2.add(shopBranchField, 1, r2++);

        grid2.add(new Label("UPI ID (For QR):"), 0, r2);
        grid2.add(shopUpiIdField, 1, r2++);

        card2.getChildren().addAll(card2Title, grid2);

        // -------------------------------------------------------------
        // CARD 3: STORE POLICY, HARDWARE & BACKUP
        // -------------------------------------------------------------
        VBox card3 = new VBox(12);
        card3.setPadding(new Insets(16));
        card3.setStyle("-fx-background-color: #ffffff; -fx-border-color: #e2e8f0; -fx-border-radius: 8; -fx-background-radius: 8;");

        Label card3Title = new Label("3. Store Policy, Hardware & Backup Preferences");
        card3Title.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #0f172a;");

        GridPane grid3 = new GridPane();
        grid3.setHgap(15);
        grid3.setVgap(12);

        ComboBox<ThermalWidth> thermalWidthCombo = new ComboBox<>(FXCollections.observableArrayList(ThermalWidth.WIDTH_58MM, ThermalWidth.WIDTH_80MM));
        thermalWidthCombo.setValue(currentProfile.thermalReceiptWidth());

        TextField termsField = new TextField(currentProfile.termsAndConditions());
        termsField.setPromptText("Terms & conditions printed on receipts");
        termsField.setPrefWidth(350);

        TextField staffDiscField = new TextField(currentProfile.staffMaxDiscountPercent().toPlainString());
        TextField returnLimitField = new TextField(String.valueOf(currentProfile.staffReturnApprovalLimitPaise()));
        CheckBox negativeStockCheck = new CheckBox("Allow Negative Stock");
        negativeStockCheck.setSelected(currentProfile.allowNegativeStock());

        TextField backupRetentionField = new TextField(String.valueOf(currentProfile.backupRetentionCount()));

        TextField backupFolderField = new TextField(currentProfile.backupExternalDirectory());
        backupFolderField.setPrefWidth(280);
        Button browseBackupBtn = new Button("Browse...");
        browseBackupBtn.setOnAction(e -> {
            DirectoryChooser dc = new DirectoryChooser();
            dc.setTitle("Select External Backup Directory");
            File f = dc.showDialog(box.getScene().getWindow());
            if (f != null) {
                backupFolderField.setText(f.getAbsolutePath());
            }
        });
        HBox backupFolderRow = new HBox(8, backupFolderField, browseBackupBtn);
        backupFolderRow.setAlignment(Pos.CENTER_LEFT);

        ComboBox<AppLocale> languageCombo = new ComboBox<>(FXCollections.observableArrayList(AppLocale.ENGLISH, AppLocale.HINDI));
        languageCombo.setValue(context.getI18nService().getCurrentLocale());

        int r3 = 0;
        grid3.add(new Label("Thermal Printer Width:"), 0, r3);
        grid3.add(thermalWidthCombo, 1, r3++);

        grid3.add(new Label("Terms & Conditions:"), 0, r3);
        grid3.add(termsField, 1, r3++);

        grid3.add(new Label("Staff Max Discount %:"), 0, r3);
        grid3.add(staffDiscField, 1, r3++);

        grid3.add(new Label("Staff Return Approval Limit (paise):"), 0, r3);
        grid3.add(returnLimitField, 1, r3++);

        grid3.add(new Label("Stock Policy:"), 0, r3);
        grid3.add(negativeStockCheck, 1, r3++);

        grid3.add(new Label("Backup Retention Count:"), 0, r3);
        grid3.add(backupRetentionField, 1, r3++);

        grid3.add(new Label("External Backup Directory:"), 0, r3);
        grid3.add(backupFolderRow, 1, r3++);

        grid3.add(new Label("Language / भाषा:"), 0, r3);
        grid3.add(languageCombo, 1, r3++);

        card3.getChildren().addAll(card3Title, grid3);

        // -------------------------------------------------------------
        // SAVE BUTTON & ACTION ROW
        // -------------------------------------------------------------
        HBox actionRow = new HBox(15);
        actionRow.setAlignment(Pos.CENTER_LEFT);

        Button saveSettingsBtn = new Button("💾 Save All Shop & Billing Settings");
        saveSettingsBtn.setStyle("-fx-background-color: #2563eb; -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 14px; -fx-padding: 10 24; -fx-background-radius: 6; -fx-cursor: hand;");
        if (!context.isOwner()) {
            saveSettingsBtn.setDisable(true);
        }

        saveSettingsBtn.setOnAction(e -> {
            try {
                long userId = context.getCurrentUser() != null ? context.getCurrentUser().id() : 1L;

                String firmName = shopNameField.getText().trim();
                if (firmName.isEmpty()) {
                    new Alert(Alert.AlertType.ERROR, "Firm / Shop Name cannot be blank", ButtonType.OK).showAndWait();
                    return;
                }

                String selectedState = stateCombo.getValue();
                String stateName = "Rajasthan";
                String stateCode = "08";
                if (selectedState != null && selectedState.contains("(") && selectedState.contains(")")) {
                    stateName = selectedState.substring(0, selectedState.indexOf('(')).trim();
                    stateCode = selectedState.substring(selectedState.indexOf('(') + 1, selectedState.indexOf(')')).trim();
                }

                // 1. Save billing details
                ShopSettingsService.ShopBillingDetails updatedBilling = new ShopSettingsService.ShopBillingDetails(
                        firmName,
                        shopTaglineField.getText().trim(),
                        shopAddressField.getText().trim(),
                        shopPhoneField.getText().trim(),
                        shopEmailField.getText().trim(),
                        shopGstinField.getText().trim(),
                        stateName,
                        stateCode,
                        shopPanField.getText().trim(),
                        shopBankNameField.getText().trim(),
                        shopAccountNoField.getText().trim(),
                        shopIfscField.getText().trim(),
                        shopBranchField.getText().trim(),
                        shopUpiIdField.getText().trim()
                );
                context.getShopSettingsService().updateShopBillingDetails(userId, updatedBilling);

                // 2. Save profile settings
                BigDecimal disc = new BigDecimal(staffDiscField.getText().trim());
                long retLimit = Long.parseLong(returnLimitField.getText().trim());
                int retention = Integer.parseInt(backupRetentionField.getText().trim());

                ShopProfile updatedProfile = new ShopProfile(
                        firmName,
                        shopTaglineField.getText().trim(),
                        shopAddressField.getText().trim(),
                        shopPhoneField.getText().trim(),
                        shopGstinField.getText().trim(),
                        thermalWidthCombo.getValue(),
                        termsField.getText().trim(),
                        disc,
                        currentProfile.staffCanRegisterSale(),
                        retLimit,
                        negativeStockCheck.isSelected(),
                        retention,
                        backupFolderField.getText().trim()
                );
                context.getShopSettingsService().updateShopProfile(userId, updatedProfile);

                // 3. Save locale
                context.getI18nService().setLocale(languageCombo.getValue(), userId);

                new Alert(Alert.AlertType.INFORMATION, "Settings saved successfully!\nAll new bills will print with this firm name, GST, bank & QR code.", ButtonType.OK).showAndWait();
            } catch (Exception ex) {
                new Alert(Alert.AlertType.ERROR, "Failed to save settings: " + ex.getMessage(), ButtonType.OK).showAndWait();
            }
        });

        actionRow.getChildren().add(saveSettingsBtn);

        box.getChildren().addAll(headerBanner, card1, card2, card3, actionRow);

        ScrollPane scrollPane = new ScrollPane(box);
        scrollPane.setFitToWidth(true);
        scrollPane.setStyle("-fx-background: #f8fafc; -fx-background-color: transparent;");
        return scrollPane;
    }

    private VBox buildUsersTab() {
        VBox box = new VBox(15);
        box.setPadding(new Insets(20));

        HBox actionRow = new HBox(15);
        actionRow.setAlignment(Pos.CENTER_LEFT);

        Button addStaffBtn = new Button("+ Add New Staff Account");
        addStaffBtn.setStyle("-fx-background-color: #059669; -fx-text-fill: white; -fx-font-weight: bold; -fx-background-radius: 4;");
        addStaffBtn.setOnAction(e -> openAddUserDialog());

        Button toggleActiveBtn = new Button("Toggle Active / Deactivate");
        toggleActiveBtn.setStyle("-fx-background-color: #f1f5f9; -fx-border-color: #cbd5e1; -fx-border-radius: 4;");
        toggleActiveBtn.setOnAction(e -> handleToggleActive());

        actionRow.getChildren().addAll(addStaffBtn, toggleActiveBtn);

        TableColumn<User, String> colId = new TableColumn<>("ID");
        colId.setCellValueFactory(d -> new SimpleStringProperty(String.valueOf(d.getValue().id())));
        colId.setPrefWidth(50);

        TableColumn<User, String> colUser = new TableColumn<>("Username");
        colUser.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().username()));
        colUser.setPrefWidth(140);

        TableColumn<User, String> colName = new TableColumn<>("Full Name");
        colName.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().fullName()));
        colName.setPrefWidth(180);

        TableColumn<User, String> colRole = new TableColumn<>("Role");
        colRole.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().role().name()));
        colRole.setPrefWidth(100);

        TableColumn<User, String> colStatus = new TableColumn<>("Status");
        colStatus.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().isActive() ? "ACTIVE" : "INACTIVE"));
        colStatus.setPrefWidth(100);

        userTable.getColumns().addAll(colId, colUser, colName, colRole, colStatus);
        VBox.setVgrow(userTable, Priority.ALWAYS);

        box.getChildren().addAll(actionRow, userTable);
        loadUsers();
        return box;
    }

    private void loadUsers() {
        if (context.isOwner()) {
            List<User> list = context.getUserService().getAllUsers(context.getCurrentUser().id());
            userData.setAll(list);
        }
    }

    private void openAddUserDialog() {
        Dialog<Boolean> dialog = new Dialog<>();
        dialog.setTitle("Create Staff Account");
        dialog.setHeaderText("Enter details for new staff member");

        ButtonType confirm = new ButtonType("Create Account", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(confirm, ButtonType.CANCEL);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20));

        TextField fullNameField = new TextField();
        TextField usernameField = new TextField();
        PasswordField passwordField = new PasswordField();

        grid.add(new Label("Full Name:"), 0, 0);
        grid.add(fullNameField, 1, 0);
        grid.add(new Label("Username:"), 0, 1);
        grid.add(usernameField, 1, 1);
        grid.add(new Label("Password (min 6 chars):"), 0, 2);
        grid.add(passwordField, 1, 2);

        dialog.getDialogPane().setContent(grid);

        dialog.setResultConverter(btn -> {
            if (btn == confirm) {
                try {
                    context.getUserService().createStaffUser(
                            context.getCurrentUser().id(),
                            usernameField.getText(),
                            passwordField.getText(),
                            fullNameField.getText()
                    );
                    new Alert(Alert.AlertType.INFORMATION, "Staff account created successfully!", ButtonType.OK).showAndWait();
                    loadUsers();
                    return true;
                } catch (Exception ex) {
                    new Alert(Alert.AlertType.ERROR, "Failed to create user: " + ex.getMessage(), ButtonType.OK).showAndWait();
                    return false;
                }
            }
            return null;
        });

        dialog.showAndWait();
    }

    private void handleToggleActive() {
        User selected = userTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            new Alert(Alert.AlertType.WARNING, "Please select a user", ButtonType.OK).showAndWait();
            return;
        }

        try {
            boolean newActive = !selected.isActive();
            context.getUserService().setUserActive(context.getCurrentUser().id(), selected.id(), newActive);
            new Alert(Alert.AlertType.INFORMATION, "User status updated!", ButtonType.OK).showAndWait();
            loadUsers();
        } catch (Exception ex) {
            new Alert(Alert.AlertType.ERROR, "Failed: " + ex.getMessage(), ButtonType.OK).showAndWait();
        }
    }

    private VBox buildAuditTab() {
        VBox box = new VBox(15);
        box.setPadding(new Insets(20));

        Button refreshBtn = new Button("Refresh Audit Log");
        refreshBtn.setStyle("-fx-background-color: #f1f5f9; -fx-border-color: #cbd5e1; -fx-border-radius: 4;");
        refreshBtn.setOnAction(e -> loadAuditLogs());

        TableColumn<AuditLogRecord, String> colTime = new TableColumn<>("Time");
        colTime.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().createdAt()));
        colTime.setPrefWidth(150);

        TableColumn<AuditLogRecord, String> colUser = new TableColumn<>("User");
        colUser.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().username() != null ? d.getValue().username() : String.valueOf(d.getValue().userId())));
        colUser.setPrefWidth(100);

        TableColumn<AuditLogRecord, String> colAction = new TableColumn<>("Action");
        colAction.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().action()));
        colAction.setPrefWidth(160);

        TableColumn<AuditLogRecord, String> colEntity = new TableColumn<>("Entity");
        colEntity.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().entityName() + (d.getValue().entityId() != null ? ":" + d.getValue().entityId() : "")));
        colEntity.setPrefWidth(140);

        TableColumn<AuditLogRecord, String> colReason = new TableColumn<>("Reason / Details");
        colReason.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().reason() != null ? d.getValue().reason() : ""));
        colReason.setPrefWidth(250);

        auditTable.getColumns().addAll(colTime, colUser, colAction, colEntity, colReason);
        VBox.setVgrow(auditTable, Priority.ALWAYS);

        box.getChildren().addAll(refreshBtn, auditTable);
        loadAuditLogs();
        return box;
    }

    private void loadAuditLogs() {
        if (context.isOwner()) {
            List<AuditLogRecord> list = context.getAuditLogService().getRecentLogs(context.getCurrentUser().id(), 50);
            auditData.setAll(list);
        }
    }

    private VBox buildBackupTab() {
        VBox box = new VBox(15);
        box.setPadding(new Insets(20));

        HBox actionRow = new HBox(15);
        actionRow.setAlignment(Pos.CENTER_LEFT);

        Button backupBtn = new Button("Run Full Backup Now");
        backupBtn.setStyle("-fx-background-color: #059669; -fx-text-fill: white; -fx-font-weight: bold; -fx-background-radius: 4;");
        backupBtn.setOnAction(e -> {
            try {
                BackupRecord b = context.getBackupService().performBackup(BackupType.MANUAL, null);
                new Alert(Alert.AlertType.INFORMATION, "Backup created at:\n" + b.backupFilePath(), ButtonType.OK).showAndWait();
                loadBackups();
            } catch (Exception ex) {
                new Alert(Alert.AlertType.ERROR, "Backup error: " + ex.getMessage(), ButtonType.OK).showAndWait();
            }
        });

        Button schedBackupBtn = new Button("Run Scheduled Backup & Retention");
        schedBackupBtn.setStyle("-fx-background-color: #0d9488; -fx-text-fill: white; -fx-font-weight: bold; -fx-background-radius: 4;");
        schedBackupBtn.setOnAction(e -> {
            try {
                long userId = context.getCurrentUser() != null ? context.getCurrentUser().id() : 1L;
                BackupRecord b = context.getScheduledBackupService().triggerScheduledBackupNow(userId);
                new Alert(Alert.AlertType.INFORMATION, "Scheduled backup & retention complete:\n" + b.backupFilePath(), ButtonType.OK).showAndWait();
                loadBackups();
            } catch (Exception ex) {
                new Alert(Alert.AlertType.ERROR, "Scheduled backup error: " + ex.getMessage(), ButtonType.OK).showAndWait();
            }
        });

        Button restoreBtn = new Button("Restore Database From Backup (Owner Only)");
        restoreBtn.setStyle("-fx-background-color: #dc2626; -fx-text-fill: white; -fx-font-weight: bold; -fx-background-radius: 4;");
        if (!context.isOwner()) {
            restoreBtn.setDisable(true);
        }
        restoreBtn.setOnAction(e -> handleRestore());

        Button diagBtn = new Button("Export Diagnostics ZIP");
        diagBtn.setStyle("-fx-background-color: #0284c7; -fx-text-fill: white; -fx-font-weight: bold; -fx-background-radius: 4;");
        diagBtn.setOnAction(e -> exportDiagnostics());

        actionRow.getChildren().addAll(backupBtn, schedBackupBtn, restoreBtn, diagBtn);

        TableColumn<BackupRecord, String> colPath = new TableColumn<>("Backup File");
        colPath.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().backupFilePath()));
        colPath.setPrefWidth(320);

        TableColumn<BackupRecord, String> colType = new TableColumn<>("Type");
        colType.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().backupType().name()));
        colType.setPrefWidth(120);

        TableColumn<BackupRecord, String> colStatus = new TableColumn<>("Status");
        colStatus.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().status()));
        colStatus.setPrefWidth(90);

        TableColumn<BackupRecord, String> colSize = new TableColumn<>("Size (KB)");
        colSize.setCellValueFactory(d -> new SimpleStringProperty(String.valueOf(d.getValue().fileSizeBytes() / 1024)));
        colSize.setPrefWidth(90);

        TableColumn<BackupRecord, String> colDate = new TableColumn<>("Created At");
        colDate.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().createdAt()));
        colDate.setPrefWidth(160);

        backupTable.getColumns().addAll(colPath, colType, colStatus, colSize, colDate);
        VBox.setVgrow(backupTable, Priority.ALWAYS);

        box.getChildren().addAll(actionRow, backupTable);
        loadBackups();
        return box;
    }

    private void loadBackups() {
        List<BackupRecord> list = context.getBackupService().getRecentBackups(20);
        backupData.setAll(list);
    }

    private void handleRestore() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Select SQLite Backup File to Restore");
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("SQLite Database (*.db)", "*.db"));

        File file = fileChooser.showOpenDialog(screenManager.getStage());
        if (file != null) {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    "Are you sure you want to restore from:\n" + file.getAbsolutePath() +
                            "\n\nAn automatic safety backup will be created before restoring.",
                    ButtonType.YES, ButtonType.NO);
            confirm.setTitle("Confirm Database Restore");
            Optional<ButtonType> choice = confirm.showAndWait();
            if (choice.isPresent() && choice.get() == ButtonType.YES) {
                try {
                    context.getBackupService().restoreBackup(context.getCurrentUser().id(), file.toPath());
                    new Alert(Alert.AlertType.INFORMATION, "Database successfully restored and verified!", ButtonType.OK).showAndWait();
                    loadBackups();
                } catch (Exception ex) {
                    new Alert(Alert.AlertType.ERROR, "Restore failed: " + ex.getMessage(), ButtonType.OK).showAndWait();
                }
            }
        }
    }

    private void exportDiagnostics() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Save Diagnostics ZIP");
        fc.setInitialFileName("udhyamsetu_diagnostics_" +
                context.getClock().now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".zip");
        File f = fc.showSaveDialog(screenManager.getStage());
        if (f != null) {
            try {
                context.getDiagnosticsService().exportDiagnostics(f.toPath());
                new Alert(Alert.AlertType.INFORMATION, "Diagnostics ZIP exported successfully!", ButtonType.OK).showAndWait();
            } catch (Exception ex) {
                new Alert(Alert.AlertType.ERROR, "Export error: " + ex.getMessage(), ButtonType.OK).showAndWait();
            }
        }
    }

    private VBox buildWatchdogTab() {
        VBox box = new VBox(15);
        box.setPadding(new Insets(20));

        // Date filter row
        HBox filterRow = new HBox(15);
        filterRow.setAlignment(Pos.CENTER_LEFT);

        DatePicker fromPicker = new DatePicker(context.getClock().today().minusDays(30));
        DatePicker toPicker = new DatePicker(context.getClock().today());

        Button scanBtn = new Button("Run Anomaly Scan");
        scanBtn.setStyle("-fx-background-color: #dc2626; -fx-text-fill: white; -fx-font-weight: bold; -fx-background-radius: 4;");

        filterRow.getChildren().addAll(
                new Label("From:"), fromPicker,
                new Label("To:"), toPicker,
                scanBtn
        );

        // Metric badges ribbon
        HBox metricsRibbon = new HBox(15);
        Label totalBadge = new Label("0");
        Label highBadge = new Label("0");
        Label medBadge = new Label("0");
        Label lowBadge = new Label("0");

        metricsRibbon.getChildren().addAll(
                createWatchdogCard("TOTAL ANOMALIES", totalBadge, "#1e293b"),
                createWatchdogCard("HIGH SEVERITY", highBadge, "#dc2626"),
                createWatchdogCard("MEDIUM SEVERITY", medBadge, "#ea580c"),
                createWatchdogCard("LOW SEVERITY", lowBadge, "#d97706")
        );

        // Table
        ObservableList<AuditAnomalyRecord> anomalyData = FXCollections.observableArrayList();
        TableView<AuditAnomalyRecord> anomalyTable = new TableView<>(anomalyData);
        VBox.setVgrow(anomalyTable, Priority.ALWAYS);

        TableColumn<AuditAnomalyRecord, String> colTime = new TableColumn<>("Timestamp");
        colTime.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().occurredAt()));
        colTime.setPrefWidth(140);

        TableColumn<AuditAnomalyRecord, String> colCat = new TableColumn<>("Category");
        colCat.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().category().name()));
        colCat.setPrefWidth(160);

        TableColumn<AuditAnomalyRecord, String> colSev = new TableColumn<>("Severity");
        colSev.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().severity().name()));
        colSev.setPrefWidth(90);

        TableColumn<AuditAnomalyRecord, String> colTitle = new TableColumn<>("Anomaly Title");
        colTitle.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().title()));
        colTitle.setPrefWidth(260);

        TableColumn<AuditAnomalyRecord, String> colUser = new TableColumn<>("User");
        colUser.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().username() != null ? c.getValue().username() : "-"));
        colUser.setPrefWidth(100);

        TableColumn<AuditAnomalyRecord, String> colAmount = new TableColumn<>("Financial Impact");
        colAmount.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().monetaryAmount() != null ? c.getValue().monetaryAmount().toFormattedString() : "-"));
        colAmount.setPrefWidth(120);

        anomalyTable.getColumns().addAll(List.of(colTime, colCat, colSev, colTitle, colUser, colAmount));

        // Detail Inspector Card
        VBox inspector = new VBox(6);
        inspector.setPadding(new Insets(12, 16, 12, 16));
        inspector.setStyle("-fx-background-color: #ffffff; -fx-border-color: #cbd5e1; -fx-border-radius: 6;");

        Label inspectorHeader = new Label("Anomaly Details & Recommended Action");
        inspectorHeader.setStyle("-fx-font-weight: bold; -fx-font-size: 13px; -fx-text-fill: #0f172a;");

        Label detailLabel = new Label("Select an anomaly from the table above to view full forensic explanation.");
        detailLabel.setWrapText(true);
        detailLabel.setStyle("-fx-text-fill: #475569; -fx-font-size: 12px;");

        inspector.getChildren().addAll(inspectorHeader, detailLabel);

        anomalyTable.getSelectionModel().selectedItemProperty().addListener((obs, oldV, selected) -> {
            if (selected != null) {
                detailLabel.setText(String.format("Reference: %s\n\nExplanation:\n%s\n\nFinancial Impact: %s",
                        selected.entityRef(), selected.description(),
                        selected.monetaryAmount() != null ? selected.monetaryAmount().toFormattedString() : "None"));
            } else {
                detailLabel.setText("Select an anomaly from the table above to view full forensic explanation.");
            }
        });

        Runnable scan = () -> {
            try {
                long userId = context.getCurrentUser() != null ? context.getCurrentUser().id() : 1L;
                AuditAnomalyReport report = context.getAuditAnomalyService().scanAnomalies(fromPicker.getValue(), toPicker.getValue(), userId);
                anomalyData.setAll(report.anomalies());

                totalBadge.setText(String.valueOf(report.totalAnomalies()));
                highBadge.setText(String.valueOf(report.highSeverityCount()));
                medBadge.setText(String.valueOf(report.mediumSeverityCount()));
                lowBadge.setText(String.valueOf(report.lowSeverityCount()));
            } catch (Exception ex) {
                new Alert(Alert.AlertType.ERROR, "Failed to scan anomalies: " + ex.getMessage(), ButtonType.OK).showAndWait();
            }
        };

        scanBtn.setOnAction(e -> scan.run());

        box.getChildren().addAll(filterRow, metricsRibbon, anomalyTable, inspector);
        return box;
    }

    private VBox createWatchdogCard(String title, Label valLabel, String color) {
        VBox card = new VBox(4);
        card.setPadding(new Insets(10, 16, 10, 16));
        card.setPrefWidth(200);
        card.setStyle("-fx-background-color: #ffffff; -fx-border-color: #e2e8f0; -fx-border-radius: 6;");

        Label t = new Label(title);
        t.setStyle("-fx-font-size: 10px; -fx-font-weight: bold; -fx-text-fill: #64748b;");

        valLabel.setStyle("-fx-font-size: 20px; -fx-font-weight: bold; -fx-text-fill: " + color + ";");

        card.getChildren().addAll(t, valLabel);
        return card;
    }
}
