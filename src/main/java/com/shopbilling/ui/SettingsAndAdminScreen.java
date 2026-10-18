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

        Tab settingsTab = new Tab("Shop Settings", buildSettingsTab());
        Tab usersTab = new Tab("User Accounts", buildUsersTab());
        Tab auditTab = new Tab("Audit Trail (FR-44)", buildAuditTab());
        Tab backupTab = new Tab("Backups & Diagnostics", buildBackupTab());

        if (!context.isOwner()) {
            usersTab.setDisable(true);
            auditTab.setDisable(true);
        }

        tabPane.getTabs().addAll(settingsTab, usersTab, auditTab, backupTab);
        root.setCenter(tabPane);

        return root;
    }

    private VBox buildSettingsTab() {
        VBox box = new VBox(20);
        box.setPadding(new Insets(25));

        GridPane grid = new GridPane();
        grid.setHgap(15);
        grid.setVgap(15);
        grid.setPadding(new Insets(20));
        grid.setStyle("-fx-background-color: #ffffff; -fx-border-color: #e2e8f0; -fx-border-radius: 6;");

        TextField shopNameField = new TextField();
        TextField shopPhoneField = new TextField();
        TextField shopGstinField = new TextField();
        ComboBox<ThermalWidth> thermalWidthCombo = new ComboBox<>(FXCollections.observableArrayList(ThermalWidth.WIDTH_58MM, ThermalWidth.WIDTH_80MM));
        TextField staffDiscField = new TextField();
        TextField returnLimitField = new TextField();
        CheckBox negativeStockCheck = new CheckBox("Allow Negative Stock");
        TextField backupRetentionField = new TextField();
        TextField backupFolderField = new TextField();
        backupFolderField.setPrefWidth(300);

        ShopProfile current = context.getShopSettingsService().getShopProfile();
        shopNameField.setText(current.shopName());
        shopPhoneField.setText(current.shopPhone());
        shopGstinField.setText(current.shopGstin());
        thermalWidthCombo.setValue(current.thermalReceiptWidth());
        staffDiscField.setText(current.staffMaxDiscountPercent().toPlainString());
        returnLimitField.setText(String.valueOf(current.staffReturnApprovalLimitPaise()));
        negativeStockCheck.setSelected(current.allowNegativeStock());
        backupRetentionField.setText(String.valueOf(current.backupRetentionCount()));
        backupFolderField.setText(current.backupExternalDirectory());

        Button saveSettingsBtn = new Button("Save Settings");
        saveSettingsBtn.setStyle("-fx-background-color: #2563eb; -fx-text-fill: white; -fx-font-weight: bold; -fx-background-radius: 4;");
        if (!context.isOwner()) {
            saveSettingsBtn.setDisable(true);
        }

        saveSettingsBtn.setOnAction(e -> {
            try {
                long userId = context.getCurrentUser() != null ? context.getCurrentUser().id() : 1L;
                BigDecimal disc = new BigDecimal(staffDiscField.getText().trim());
                long retLimit = Long.parseLong(returnLimitField.getText().trim());
                int retention = Integer.parseInt(backupRetentionField.getText().trim());

                ShopProfile updated = new ShopProfile(
                        shopNameField.getText().trim(),
                        current.shopTagline(),
                        current.shopAddress(),
                        shopPhoneField.getText().trim(),
                        shopGstinField.getText().trim(),
                        thermalWidthCombo.getValue(),
                        current.termsAndConditions(),
                        disc,
                        current.staffCanRegisterSale(),
                        retLimit,
                        negativeStockCheck.isSelected(),
                        retention,
                        backupFolderField.getText().trim()
                );

                context.getShopSettingsService().updateShopProfile(userId, updated);
                new Alert(Alert.AlertType.INFORMATION, "Settings saved successfully!", ButtonType.OK).showAndWait();
            } catch (Exception ex) {
                new Alert(Alert.AlertType.ERROR, "Failed to save settings: " + ex.getMessage(), ButtonType.OK).showAndWait();
            }
        });

        grid.add(new Label("Shop Name:"), 0, 0);
        grid.add(shopNameField, 1, 0);
        grid.add(new Label("Shop Phone:"), 0, 1);
        grid.add(shopPhoneField, 1, 1);
        grid.add(new Label("Shop GSTIN:"), 0, 2);
        grid.add(shopGstinField, 1, 2);
        grid.add(new Label("Thermal Printer Width:"), 0, 3);
        grid.add(thermalWidthCombo, 1, 3);
        grid.add(new Label("Staff Max Discount %:"), 0, 4);
        grid.add(staffDiscField, 1, 4);
        grid.add(new Label("Staff Return Approval Limit (paise):"), 0, 5);
        grid.add(returnLimitField, 1, 5);
        grid.add(new Label("Stock Policy:"), 0, 6);
        grid.add(negativeStockCheck, 1, 6);
        grid.add(new Label("Backup Retention Count:"), 0, 7);
        grid.add(backupRetentionField, 1, 7);
        grid.add(new Label("External Backup Directory:"), 0, 8);
        grid.add(backupFolderField, 1, 8);
        grid.add(saveSettingsBtn, 1, 9);

        box.getChildren().addAll(grid);
        return box;
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
}
