package com.shopbilling.ui;

import com.shopbilling.model.*;
import com.shopbilling.service.*;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.io.File;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Production-Grade GST HSN Management, Classification & Verification Hub (Section 12).
 * Features 6 dedicated tabs:
 * 1. Official HSN Directory (Search, Add, Edit, Delete, Chapter Filter)
 * 2. Product Classification Approvals (Pending, Suggested, Verified, Review Required, Manual Override)
 * 3. Product Groups Hierarchy (Tally-inspired Group inheritance & default HSN/GST rates)
 * 4. Statutory GST Rate Master (Effective-dated schedules & notification citations)
 * 5. Classification Audit Trail (Traceable history of all changes & legal justifications)
 * 6. Excel Import & Batch History (Apache POI high-speed importer with batch logs)
 */
public class HsnManagementScreen {

    private final ScreenManager screenManager;
    private final AppContext context;
    private final HsnService hsnService;
    private final HsnImportService importService;
    private final HsnVerificationService verificationService;
    private final ProductGroupService groupService;
    private final GstRateService gstRateService;
    private final HsnClassificationEngine classificationEngine;

    // Tab 1: Directory
    private final ObservableList<HsnItem> hsnTableData = FXCollections.observableArrayList();
    private TableView<HsnItem> hsnTableView;
    private TextField hsnSearchField;
    private ComboBox<String> chapterFilterCombo;
    private Label directoryStatsLabel;

    // Tab 2: Classification Approvals
    private final ObservableList<Product> productsData = FXCollections.observableArrayList();
    private TableView<Product> productsTableView;
    private ComboBox<String> statusFilterCombo;
    private Label approvalStatsLabel;

    // Tab 3: Product Groups
    private final ObservableList<ProductGroup> groupsData = FXCollections.observableArrayList();
    private TableView<ProductGroup> groupsTableView;

    // Tab 4: GST Rate Master
    private final ObservableList<GstRateItem> gstRateData = FXCollections.observableArrayList();
    private TableView<GstRateItem> gstRateTableView;

    // Tab 5: Audit Trail
    private final ObservableList<HsnVerificationAuditRecord> auditData = FXCollections.observableArrayList();
    private TableView<HsnVerificationAuditRecord> auditTableView;

    // Tab 6: Import History
    private final ObservableList<HsnImportHistoryItem> importHistoryData = FXCollections.observableArrayList();
    private TableView<HsnImportHistoryItem> importHistoryTableView;

    public HsnManagementScreen(ScreenManager screenManager) {
        this.screenManager = screenManager;
        this.context = screenManager.getContext();
        this.hsnService = context.getHsnService();
        this.importService = context.getHsnImportService();
        this.verificationService = context.getHsnVerificationService();
        this.groupService = context.getProductGroupService();
        this.gstRateService = context.getGstRateService();
        this.classificationEngine = context.getHsnClassificationEngine();
    }

    public Parent getView() {
        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color: #f8fafc;");

        // Top Header
        VBox header = createHeader();
        root.setTop(header);

        // TabPane
        TabPane tabPane = new TabPane();
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabPane.setStyle("-fx-font-size: 13px;");

        Tab tab1 = new Tab("1. HSN Master Directory", createDirectoryTab());
        Tab tab2 = new Tab("2. Classification Approvals", createApprovalsTab());
        Tab tab3 = new Tab("3. Product Groups (Tally Hierarchy)", createGroupsTab());
        Tab tab4 = new Tab("4. GST Rate Master", createGstRatesTab());
        Tab tab5 = new Tab("5. Classification Audit Trail", createAuditTab());
        Tab tab6 = new Tab("6. Excel Import & Logs", createImportTab());

        tabPane.getTabs().addAll(tab1, tab2, tab3, tab4, tab5, tab6);
        root.setCenter(tabPane);

        loadAllData();
        return root;
    }

    private VBox createHeader() {
        HBox topBox = new HBox(15);
        topBox.setAlignment(Pos.CENTER_LEFT);
        topBox.setPadding(new Insets(12, 20, 12, 20));
        topBox.setStyle("-fx-background-color: #ffffff; -fx-border-color: #e2e8f0; -fx-border-width: 0 0 1 0;");

        Button backBtn = new Button("← Back to Dashboard");
        backBtn.setStyle("-fx-background-color: #f1f5f9; -fx-text-fill: #334155; -fx-font-weight: bold; -fx-padding: 8 16; -fx-background-radius: 6;");
        backBtn.setOnAction(e -> screenManager.showHome());

        Label title = new Label("GST HSN Classification & Statutory Verification Hub");
        title.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: #0f172a;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label disclaimerBadge = new Label("Official CBIC Customs Tariff & GST Rate Master");
        disclaimerBadge.setStyle("-fx-background-color: #eff6ff; -fx-text-fill: #1d4ed8; -fx-font-size: 11px; -fx-font-weight: bold; -fx-padding: 6 12; -fx-background-radius: 12; -fx-border-color: #bfdbfe; -fx-border-radius: 12;");

        topBox.getChildren().addAll(backBtn, title, spacer, disclaimerBadge);
        return new VBox(topBox);
    }

    // =========================================================================
    // TAB 1: HSN Master Directory
    // =========================================================================
    private Node createDirectoryTab() {
        VBox layout = new VBox(12);
        layout.setPadding(new Insets(16));

        // Controls Bar
        HBox controls = new HBox(12);
        controls.setAlignment(Pos.CENTER_LEFT);

        hsnSearchField = new TextField();
        hsnSearchField.setPromptText("Search HSN code, electrical description, wire, switch, mcb...");
        hsnSearchField.setPrefWidth(380);
        hsnSearchField.textProperty().addListener((obs, o, n) -> searchDirectory());

        chapterFilterCombo = new ComboBox<>(FXCollections.observableArrayList(
                "All Chapters", "85: Electrical & Electronics", "84: Machinery & Fans", "39: PVC Pipes & Plastics", "69: Ceramic Sanitary", "94: Luminaires & Lighting"
        ));
        chapterFilterCombo.setValue("All Chapters");
        chapterFilterCombo.setOnAction(e -> searchDirectory());

        Button addHsnBtn = new Button("+ Add HSN Entry");
        addHsnBtn.setStyle("-fx-background-color: #0284c7; -fx-text-fill: white; -fx-font-weight: bold; -fx-padding: 7 14; -fx-background-radius: 6;");
        addHsnBtn.setOnAction(e -> showAddHsnDialog());

        directoryStatsLabel = new Label("Loading directory...");
        directoryStatsLabel.setStyle("-fx-text-fill: #64748b; -fx-font-size: 12px;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        controls.getChildren().addAll(new Label("Filter:"), hsnSearchField, chapterFilterCombo, addHsnBtn, spacer, directoryStatsLabel);

        // Table
        hsnTableView = new TableView<>();
        hsnTableView.setItems(hsnTableData);
        hsnTableView.setPlaceholder(new Label("No HSN directory entries found matching search query."));
        VBox.setVgrow(hsnTableView, Priority.ALWAYS);

        TableColumn<HsnItem, String> colCode = new TableColumn<>("HSN / Tariff Code");
        colCode.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().hsnCode()));
        colCode.setPrefWidth(140);
        colCode.setStyle("-fx-font-weight: bold; -fx-font-family: monospace;");

        TableColumn<HsnItem, String> colDesc = new TableColumn<>("Official Statutory Description");
        colDesc.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().description()));
        colDesc.setPrefWidth(420);

        TableColumn<HsnItem, String> colChapter = new TableColumn<>("Chapter");
        colChapter.setCellValueFactory(d -> new SimpleStringProperty("Ch. " + d.getValue().chapter()));
        colChapter.setPrefWidth(90);

        TableColumn<HsnItem, String> colType = new TableColumn<>("Type");
        colType.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().productType()));
        colType.setPrefWidth(90);

        TableColumn<HsnItem, String> colNotes = new TableColumn<>("GRI & Technical Notes");
        colNotes.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().technicalNotes() != null ? d.getValue().technicalNotes() : "-"));
        colNotes.setPrefWidth(300);

        TableColumn<HsnItem, Void> colActions = new TableColumn<>("Actions");
        colActions.setPrefWidth(120);
        colActions.setCellFactory(param -> new TableCell<>() {
            private final Button editBtn = new Button("Edit");
            private final Button delBtn = new Button("Del");
            private final HBox pane = new HBox(6, editBtn, delBtn);
            {
                editBtn.setStyle("-fx-background-color: #f1f5f9; -fx-text-fill: #0f172a; -fx-padding: 3 8;");
                editBtn.setOnAction(e -> {
                    HsnItem item = getTableView().getItems().get(getIndex());
                    showEditHsnDialog(item);
                });
                delBtn.setStyle("-fx-background-color: #fee2e2; -fx-text-fill: #991b1b; -fx-padding: 3 8;");
                delBtn.setOnAction(e -> {
                    HsnItem item = getTableView().getItems().get(getIndex());
                    deleteHsn(item);
                });
            }
            @Override
            protected void updateItem(Void val, boolean empty) {
                super.updateItem(val, empty);
                setGraphic(empty ? null : pane);
            }
        });

        hsnTableView.getColumns().addAll(colCode, colDesc, colChapter, colType, colNotes, colActions);
        layout.getChildren().addAll(controls, hsnTableView);
        return layout;
    }

    private void searchDirectory() {
        String q = hsnSearchField.getText() != null ? hsnSearchField.getText().trim() : "";
        String chapterSelection = chapterFilterCombo.getValue();
        String chapterCode = null;
        if (chapterSelection != null && chapterSelection.contains(":")) {
            chapterCode = chapterSelection.substring(0, chapterSelection.indexOf(':')).trim();
        }

        List<HsnItem> results = hsnService.searchHsnWithChapter(q, chapterCode, 100);
        hsnTableData.setAll(results);
        directoryStatsLabel.setText("Showing " + results.size() + " HSN records");
    }

    private void showAddHsnDialog() {
        Dialog<HsnItem> dialog = new Dialog<>();
        dialog.setTitle("Add Official HSN / Tariff Code");
        dialog.setHeaderText("Create Official HSN Classification Record");

        ButtonType saveBtnType = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveBtnType, ButtonType.CANCEL);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20));

        TextField codeField = new TextField();
        codeField.setPromptText("e.g. 85365020");
        TextArea descArea = new TextArea();
        descArea.setPromptText("Official description as per Customs Tariff Act");
        descArea.setPrefRowCount(3);
        ComboBox<String> typeCombo = new ComboBox<>(FXCollections.observableArrayList("GOODS", "SERVICES"));
        typeCombo.setValue("GOODS");
        TextArea notesArea = new TextArea();
        notesArea.setPromptText("GRI notes, voltage/material specifications");
        notesArea.setPrefRowCount(2);

        grid.add(new Label("HSN/SAC Code *:"), 0, 0);
        grid.add(codeField, 1, 0);
        grid.add(new Label("Description *:"), 0, 1);
        grid.add(descArea, 1, 1);
        grid.add(new Label("Type:"), 0, 2);
        grid.add(typeCombo, 1, 2);
        grid.add(new Label("Technical Notes:"), 0, 3);
        grid.add(notesArea, 1, 3);

        dialog.getDialogPane().setContent(grid);

        dialog.setResultConverter(btn -> {
            if (btn == saveBtnType) {
                try {
                    HsnItem item = hsnService.createHsn(
                            codeField.getText().trim(),
                            descArea.getText().trim(),
                            typeCombo.getValue(),
                            "Manual Entry",
                            notesArea.getText().trim()
                    );
                    searchDirectory();
                    return item;
                } catch (Exception ex) {
                    new Alert(Alert.AlertType.ERROR, "Failed to create HSN: " + ex.getMessage(), ButtonType.OK).showAndWait();
                }
            }
            return null;
        });
        dialog.showAndWait();
    }

    private void showEditHsnDialog(HsnItem item) {
        Dialog<HsnItem> dialog = new Dialog<>();
        dialog.setTitle("Edit HSN Record");
        dialog.setHeaderText("Update HSN " + item.hsnCode());

        ButtonType saveBtnType = new ButtonType("Update", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveBtnType, ButtonType.CANCEL);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20));

        TextField codeField = new TextField(item.hsnCode());
        TextArea descArea = new TextArea(item.description());
        descArea.setPrefRowCount(3);
        ComboBox<String> typeCombo = new ComboBox<>(FXCollections.observableArrayList("GOODS", "SERVICES"));
        typeCombo.setValue(item.productType());

        grid.add(new Label("HSN/SAC Code *:"), 0, 0);
        grid.add(codeField, 1, 0);
        grid.add(new Label("Description *:"), 0, 1);
        grid.add(descArea, 1, 1);
        grid.add(new Label("Type:"), 0, 2);
        grid.add(typeCombo, 1, 2);

        dialog.getDialogPane().setContent(grid);

        dialog.setResultConverter(btn -> {
            if (btn == saveBtnType) {
                try {
                    HsnItem updated = hsnService.updateHsn(item.id(), codeField.getText().trim(), descArea.getText().trim(), typeCombo.getValue());
                    searchDirectory();
                    return updated;
                } catch (Exception ex) {
                    new Alert(Alert.AlertType.ERROR, "Failed to update HSN: " + ex.getMessage(), ButtonType.OK).showAndWait();
                }
            }
            return null;
        });
        dialog.showAndWait();
    }

    private void deleteHsn(HsnItem item) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, "Are you sure you want to delete HSN code " + item.hsnCode() + "?", ButtonType.YES, ButtonType.NO);
        confirm.showAndWait();
        if (confirm.getResult() == ButtonType.YES) {
            try {
                hsnService.deleteHsn(item.id());
                searchDirectory();
            } catch (Exception ex) {
                new Alert(Alert.AlertType.ERROR, ex.getMessage(), ButtonType.OK).showAndWait();
            }
        }
    }

    // =========================================================================
    // TAB 2: Classification Approvals
    // =========================================================================
    private Node createApprovalsTab() {
        VBox layout = new VBox(12);
        layout.setPadding(new Insets(16));

        HBox topBox = new HBox(12);
        topBox.setAlignment(Pos.CENTER_LEFT);

        statusFilterCombo = new ComboBox<>(FXCollections.observableArrayList(
                "All Products", "PENDING Review", "SUGGESTED", "VERIFIED (Approved)", "REJECTED", "REVIEW_REQUIRED"
        ));
        statusFilterCombo.setValue("All Products");
        statusFilterCombo.setOnAction(e -> filterProducts());

        Button verifyBtn = new Button("✔ Approve & Verify Selected");
        verifyBtn.setStyle("-fx-background-color: #10b981; -fx-text-fill: white; -fx-font-weight: bold; -fx-padding: 7 14; -fx-background-radius: 6;");
        verifyBtn.setOnAction(e -> approveSelectedProduct());

        Button overrideBtn = new Button("✎ Manual Override");
        overrideBtn.setStyle("-fx-background-color: #f59e0b; -fx-text-fill: white; -fx-font-weight: bold; -fx-padding: 7 14; -fx-background-radius: 6;");
        overrideBtn.setOnAction(e -> overrideSelectedProduct());

        Button reviewBtn = new Button("⚠ Flag Review Required");
        reviewBtn.setStyle("-fx-background-color: #8b5cf6; -fx-text-fill: white; -fx-font-weight: bold; -fx-padding: 7 14; -fx-background-radius: 6;");
        reviewBtn.setOnAction(e -> flagSelectedReviewRequired());

        approvalStatsLabel = new Label();
        approvalStatsLabel.setStyle("-fx-text-fill: #64748b; -fx-font-size: 12px;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        topBox.getChildren().addAll(new Label("Filter Status:"), statusFilterCombo, verifyBtn, overrideBtn, reviewBtn, spacer, approvalStatsLabel);

        // Products Table
        productsTableView = new TableView<>();
        productsTableView.setItems(productsData);
        VBox.setVgrow(productsTableView, Priority.ALWAYS);

        TableColumn<Product, String> colName = new TableColumn<>("Product Name");
        colName.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().name()));
        colName.setPrefWidth(260);
        colName.setStyle("-fx-font-weight: bold;");

        TableColumn<Product, String> colHsn = new TableColumn<>("Assigned HSN");
        colHsn.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().hsnCode() != null ? d.getValue().hsnCode() : "[Not Assigned]"));
        colHsn.setPrefWidth(130);
        colHsn.setStyle("-fx-font-family: monospace;");

        TableColumn<Product, String> colGst = new TableColumn<>("GST Rate");
        colGst.setCellValueFactory(d -> new SimpleStringProperty(String.format("%.1f%%", d.getValue().gstRate())));
        colGst.setPrefWidth(90);

        TableColumn<Product, String> colStatus = new TableColumn<>("Verification Status");
        colStatus.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().hsnStatus() != null ? d.getValue().hsnStatus().getLabel() : "PENDING"));
        colStatus.setPrefWidth(170);
        colStatus.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setStyle("");
                } else {
                    setText(item);
                    if (item.contains("Verified")) {
                        setStyle("-fx-background-color: #dcfce7; -fx-text-fill: #166534; -fx-font-weight: bold; -fx-alignment: center;");
                    } else if (item.contains("Suggested")) {
                        setStyle("-fx-background-color: #dbeafe; -fx-text-fill: #1e40af; -fx-font-weight: bold; -fx-alignment: center;");
                    } else if (item.contains("Review")) {
                        setStyle("-fx-background-color: #ede9fe; -fx-text-fill: #5b21b6; -fx-font-weight: bold; -fx-alignment: center;");
                    } else if (item.contains("Rejected")) {
                        setStyle("-fx-background-color: #fee2e2; -fx-text-fill: #991b1b; -fx-font-weight: bold; -fx-alignment: center;");
                    } else {
                        setStyle("-fx-background-color: #fef3c7; -fx-text-fill: #92400e; -fx-font-weight: bold; -fx-alignment: center;");
                    }
                }
            }
        });

        productsTableView.getColumns().addAll(colName, colHsn, colGst, colStatus);

        layout.getChildren().addAll(topBox, productsTableView);
        return layout;
    }

    private void filterProducts() {
        String filter = statusFilterCombo.getValue();
        List<Product> all = context.getProductService().getAllProducts(false);
        if (filter == null || filter.equals("All Products")) {
            productsData.setAll(all);
        } else {
            String target = filter.split(" ")[0].toUpperCase();
            productsData.setAll(all.stream().filter(p -> p.hsnStatus() != null && p.hsnStatus().name().equalsIgnoreCase(target)).toList());
        }
        approvalStatsLabel.setText("Showing " + productsData.size() + " products");
    }

    private void approveSelectedProduct() {
        Product selected = productsTableView.getSelectionModel().getSelectedItem();
        if (selected == null) {
            new Alert(Alert.AlertType.WARNING, "Please select a product from the table first.", ButtonType.OK).showAndWait();
            return;
        }

        if (selected.hsnCode() == null || selected.hsnCode().isBlank()) {
            new Alert(Alert.AlertType.ERROR, "Selected product does not have an HSN assigned. Please assign one via Manual Override or Product Edit.", ButtonType.OK).showAndWait();
            return;
        }

        verificationService.verifyAndApprove(selected.id(), selected.hsnCode(), selected.gstRate(), 1L, "Manual approval via HSN Hub", "Owner Review");
        filterProducts();
        loadAuditData();
        new Alert(Alert.AlertType.INFORMATION, "Product '" + selected.name() + "' marked as VERIFIED & APPROVED for tax invoicing.", ButtonType.OK).showAndWait();
    }

    private void overrideSelectedProduct() {
        Product selected = productsTableView.getSelectionModel().getSelectedItem();
        if (selected == null) {
            new Alert(Alert.AlertType.WARNING, "Please select a product from the table first.", ButtonType.OK).showAndWait();
            return;
        }

        TextInputDialog dialog = new TextInputDialog(selected.hsnCode() != null ? selected.hsnCode() : "");
        dialog.setTitle("Manual HSN Override");
        dialog.setHeaderText("Override HSN for: " + selected.name());
        dialog.setContentText("Enter New HSN Code:");

        Optional<String> res = dialog.showAndWait();
        if (res.isPresent() && !res.get().isBlank()) {
            String newHsn = res.get().trim();
            TextInputDialog reasonDialog = new TextInputDialog("Trade catalog clarification");
            reasonDialog.setTitle("Override Justification Required");
            reasonDialog.setHeaderText("Legal Compliance Audit Trail");
            reasonDialog.setContentText("Enter Statutory Rationale / Justification *:");
            Optional<String> reasonRes = reasonDialog.showAndWait();

            if (reasonRes.isPresent() && !reasonRes.get().isBlank()) {
                try {
                    verificationService.manualOverride(selected.id(), newHsn, selected.gstRate(), 1L, reasonRes.get().trim());
                    filterProducts();
                    loadAuditData();
                    new Alert(Alert.AlertType.INFORMATION, "Manual override successfully applied and logged to audit trail.", ButtonType.OK).showAndWait();
                } catch (Exception ex) {
                    new Alert(Alert.AlertType.ERROR, "Override failed: " + ex.getMessage(), ButtonType.OK).showAndWait();
                }
            }
        }
    }

    private void flagSelectedReviewRequired() {
        Product selected = productsTableView.getSelectionModel().getSelectedItem();
        if (selected == null) {
            new Alert(Alert.AlertType.WARNING, "Please select a product from the table first.", ButtonType.OK).showAndWait();
            return;
        }

        TextInputDialog dialog = new TextInputDialog("Missing operating voltage / material specification");
        dialog.setTitle("Flag for Expert Review");
        dialog.setHeaderText("Flag Product: " + selected.name());
        dialog.setContentText("Notes on missing technical specifications:");

        Optional<String> res = dialog.showAndWait();
        if (res.isPresent() && !res.get().isBlank()) {
            verificationService.flagReviewRequired(selected.id(), 1L, res.get().trim());
            filterProducts();
            loadAuditData();
        }
    }

    // =========================================================================
    // TAB 3: Product Groups Hierarchy
    // =========================================================================
    private Node createGroupsTab() {
        VBox layout = new VBox(12);
        layout.setPadding(new Insets(16));

        HBox topBox = new HBox(12);
        topBox.setAlignment(Pos.CENTER_LEFT);

        Label infoLabel = new Label("Tally-inspired classification hierarchy. Individual products override group defaults.");
        infoLabel.setStyle("-fx-text-fill: #475569; -fx-font-style: italic;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button addGroupBtn = new Button("+ Add Product Group");
        addGroupBtn.setStyle("-fx-background-color: #0284c7; -fx-text-fill: white; -fx-font-weight: bold; -fx-padding: 7 14; -fx-background-radius: 6;");
        addGroupBtn.setOnAction(e -> showAddGroupDialog());

        topBox.getChildren().addAll(infoLabel, spacer, addGroupBtn);

        groupsTableView = new TableView<>();
        groupsTableView.setItems(groupsData);
        VBox.setVgrow(groupsTableView, Priority.ALWAYS);

        TableColumn<ProductGroup, String> colCode = new TableColumn<>("Group Code");
        colCode.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().groupCode()));
        colCode.setPrefWidth(140);
        colCode.setStyle("-fx-font-weight: bold;");

        TableColumn<ProductGroup, String> colName = new TableColumn<>("Group Name");
        colName.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().groupName()));
        colName.setPrefWidth(260);

        TableColumn<ProductGroup, String> colDefaultHsn = new TableColumn<>("Default HSN");
        colDefaultHsn.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().defaultHsnCode() != null ? d.getValue().defaultHsnCode() : "-"));
        colDefaultHsn.setPrefWidth(130);
        colDefaultHsn.setStyle("-fx-font-family: monospace;");

        TableColumn<ProductGroup, String> colDefaultGst = new TableColumn<>("Default GST Rate");
        colDefaultGst.setCellValueFactory(d -> new SimpleStringProperty(String.format("%.1f%%", d.getValue().defaultGstRate())));
        colDefaultGst.setPrefWidth(120);

        TableColumn<ProductGroup, String> colDesc = new TableColumn<>("Description");
        colDesc.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().description()));
        colDesc.setPrefWidth(380);

        groupsTableView.getColumns().addAll(colCode, colName, colDefaultHsn, colDefaultGst, colDesc);
        layout.getChildren().addAll(topBox, groupsTableView);
        return layout;
    }

    private void showAddGroupDialog() {
        Dialog<ProductGroup> dialog = new Dialog<>();
        dialog.setTitle("Add Product Group");
        dialog.setHeaderText("Create Product Group in Tally Classification Hierarchy");

        ButtonType saveBtnType = new ButtonType("Save Group", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveBtnType, ButtonType.CANCEL);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20));

        TextField codeField = new TextField();
        codeField.setPromptText("e.g. GRP_INVERTER");
        TextField nameField = new TextField();
        nameField.setPromptText("e.g. Inverters & Solar Converters");
        TextField hsnField = new TextField();
        hsnField.setPromptText("e.g. 85044090");
        ComboBox<Double> gstCombo = new ComboBox<>(FXCollections.observableArrayList(0.0, 5.0, 12.0, 18.0, 28.0));
        gstCombo.setValue(18.0);
        TextField descField = new TextField();
        descField.setPromptText("Group description");

        grid.add(new Label("Group Code *:"), 0, 0);
        grid.add(codeField, 1, 0);
        grid.add(new Label("Group Name *:"), 0, 1);
        grid.add(nameField, 1, 1);
        grid.add(new Label("Default HSN:"), 0, 2);
        grid.add(hsnField, 1, 2);
        grid.add(new Label("Default GST Rate (%):"), 0, 3);
        grid.add(gstCombo, 1, 3);
        grid.add(new Label("Description:"), 0, 4);
        grid.add(descField, 1, 4);

        dialog.getDialogPane().setContent(grid);
        dialog.setResultConverter(btn -> {
            if (btn == saveBtnType) {
                try {
                    ProductGroup g = groupService.createGroup(
                            codeField.getText().trim(),
                            nameField.getText().trim(),
                            hsnField.getText().trim(),
                            gstCombo.getValue(),
                            descField.getText().trim(),
                            null
                    );
                    loadGroupsData();
                    return g;
                } catch (Exception ex) {
                    new Alert(Alert.AlertType.ERROR, "Failed to create group: " + ex.getMessage(), ButtonType.OK).showAndWait();
                }
            }
            return null;
        });
        dialog.showAndWait();
    }

    // =========================================================================
    // TAB 4: GST Rate Master
    // =========================================================================
    private Node createGstRatesTab() {
        VBox layout = new VBox(12);
        layout.setPadding(new Insets(16));

        HBox topBox = new HBox(12);
        topBox.setAlignment(Pos.CENTER_LEFT);

        Label infoLabel = new Label("Statutory effective-dated GST rate schedules with official notification citations.");
        infoLabel.setStyle("-fx-text-fill: #475569;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button addRateBtn = new Button("+ Add Statutory GST Rate Revision");
        addRateBtn.setStyle("-fx-background-color: #0284c7; -fx-text-fill: white; -fx-font-weight: bold; -fx-padding: 7 14; -fx-background-radius: 6;");
        addRateBtn.setOnAction(e -> showAddGstRateDialog());

        topBox.getChildren().addAll(infoLabel, spacer, addRateBtn);

        gstRateTableView = new TableView<>();
        gstRateTableView.setItems(gstRateData);
        VBox.setVgrow(gstRateTableView, Priority.ALWAYS);

        TableColumn<GstRateItem, String> colHsn = new TableColumn<>("HSN Code Prefix");
        colHsn.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().hsnCode()));
        colHsn.setPrefWidth(130);
        colHsn.setStyle("-fx-font-weight: bold; -fx-font-family: monospace;");

        TableColumn<GstRateItem, String> colRate = new TableColumn<>("Total GST");
        colRate.setCellValueFactory(d -> new SimpleStringProperty(String.format("%.1f%%", d.getValue().gstRate())));
        colRate.setPrefWidth(90);

        TableColumn<GstRateItem, String> colCgst = new TableColumn<>("CGST");
        colCgst.setCellValueFactory(d -> new SimpleStringProperty(String.format("%.1f%%", d.getValue().cgstRate())));
        colCgst.setPrefWidth(80);

        TableColumn<GstRateItem, String> colSgst = new TableColumn<>("SGST");
        colSgst.setCellValueFactory(d -> new SimpleStringProperty(String.format("%.1f%%", d.getValue().sgstRate())));
        colSgst.setPrefWidth(80);

        TableColumn<GstRateItem, String> colIgst = new TableColumn<>("IGST");
        colIgst.setCellValueFactory(d -> new SimpleStringProperty(String.format("%.1f%%", d.getValue().igstRate())));
        colIgst.setPrefWidth(80);

        TableColumn<GstRateItem, String> colEffective = new TableColumn<>("Effective From");
        colEffective.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().effectiveFrom()));
        colEffective.setPrefWidth(120);

        TableColumn<GstRateItem, String> colNotif = new TableColumn<>("Official Notification Reference");
        colNotif.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().notificationReference()));
        colNotif.setPrefWidth(420);

        gstRateTableView.getColumns().addAll(colHsn, colRate, colCgst, colSgst, colIgst, colEffective, colNotif);
        layout.getChildren().addAll(topBox, gstRateTableView);
        return layout;
    }

    private void showAddGstRateDialog() {
        Dialog<GstRateItem> dialog = new Dialog<>();
        dialog.setTitle("Add Statutory GST Rate");
        dialog.setHeaderText("Record Statutory Notification GST Rate");

        ButtonType saveBtnType = new ButtonType("Save Rate", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveBtnType, ButtonType.CANCEL);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20));

        TextField hsnField = new TextField();
        hsnField.setPromptText("e.g. 8504 or 85446020");
        ComboBox<Double> gstCombo = new ComboBox<>(FXCollections.observableArrayList(0.0, 5.0, 12.0, 18.0, 28.0));
        gstCombo.setValue(18.0);
        TextField dateField = new TextField("2017-07-01");
        TextField notifField = new TextField("Notification No. 01/2017-Central Tax (Rate)");

        grid.add(new Label("HSN / Tariff Code *:"), 0, 0);
        grid.add(hsnField, 1, 0);
        grid.add(new Label("GST Rate (%):"), 0, 1);
        grid.add(gstCombo, 1, 1);
        grid.add(new Label("Effective From Date *:"), 0, 2);
        grid.add(dateField, 1, 2);
        grid.add(new Label("Statutory Notification *:"), 0, 3);
        grid.add(notifField, 1, 3);

        dialog.getDialogPane().setContent(grid);
        dialog.setResultConverter(btn -> {
            if (btn == saveBtnType) {
                try {
                    GstRateItem item = gstRateService.addRate(
                            hsnField.getText().trim(),
                            gstCombo.getValue(),
                            dateField.getText().trim(),
                            notifField.getText().trim()
                    );
                    loadGstRatesData();
                    return item;
                } catch (Exception ex) {
                    new Alert(Alert.AlertType.ERROR, "Failed to record GST rate: " + ex.getMessage(), ButtonType.OK).showAndWait();
                }
            }
            return null;
        });
        dialog.showAndWait();
    }

    // =========================================================================
    // TAB 5: Classification Audit Trail
    // =========================================================================
    private Node createAuditTab() {
        VBox layout = new VBox(12);
        layout.setPadding(new Insets(16));

        Label infoLabel = new Label("Traceable historical record of all HSN classification changes, statutory approvals, and manual overrides.");
        infoLabel.setStyle("-fx-text-fill: #475569;");

        auditTableView = new TableView<>();
        auditTableView.setItems(auditData);
        VBox.setVgrow(auditTableView, Priority.ALWAYS);

        TableColumn<HsnVerificationAuditRecord, String> colDate = new TableColumn<>("Timestamp");
        colDate.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().createdAt()));
        colDate.setPrefWidth(160);

        TableColumn<HsnVerificationAuditRecord, String> colAction = new TableColumn<>("Action");
        colAction.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().action()));
        colAction.setPrefWidth(110);
        colAction.setStyle("-fx-font-weight: bold;");

        TableColumn<HsnVerificationAuditRecord, String> colProd = new TableColumn<>("Product ID");
        colProd.setCellValueFactory(d -> new SimpleStringProperty("#" + d.getValue().productId()));
        colProd.setPrefWidth(90);

        TableColumn<HsnVerificationAuditRecord, String> colPrevHsn = new TableColumn<>("Previous HSN");
        colPrevHsn.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().previousHsn() != null ? d.getValue().previousHsn() : "-"));
        colPrevHsn.setPrefWidth(120);

        TableColumn<HsnVerificationAuditRecord, String> colNewHsn = new TableColumn<>("New HSN");
        colNewHsn.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().newHsn() != null ? d.getValue().newHsn() : "-"));
        colNewHsn.setPrefWidth(120);
        colNewHsn.setStyle("-fx-font-weight: bold; -fx-text-fill: #166534;");

        TableColumn<HsnVerificationAuditRecord, String> colReason = new TableColumn<>("Justification / Rationale");
        colReason.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().reason()));
        colReason.setPrefWidth(280);

        TableColumn<HsnVerificationAuditRecord, String> colSource = new TableColumn<>("Verification Source");
        colSource.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().verificationSource()));
        colSource.setPrefWidth(180);

        auditTableView.getColumns().addAll(colDate, colAction, colProd, colPrevHsn, colNewHsn, colReason, colSource);
        layout.getChildren().addAll(infoLabel, auditTableView);
        return layout;
    }

    // =========================================================================
    // TAB 6: Excel Import & Logs
    // =========================================================================
    private Node createImportTab() {
        VBox layout = new VBox(14);
        layout.setPadding(new Insets(16));

        HBox importBar = new HBox(12);
        importBar.setAlignment(Pos.CENTER_LEFT);

        Button uploadBtn = new Button("📁 Import Official HSN Directory (Excel .xlsx / .xls)");
        uploadBtn.setStyle("-fx-background-color: #0284c7; -fx-text-fill: white; -fx-font-weight: bold; -fx-padding: 9 18; -fx-background-radius: 6;");
        uploadBtn.setOnAction(e -> startExcelImport());

        Label helpText = new Label("High-speed Apache POI batch upsert. Automatically updates HSN descriptions without destroying existing product mappings.");
        helpText.setStyle("-fx-text-fill: #64748b;");

        importBar.getChildren().addAll(uploadBtn, helpText);

        Label historyTitle = new Label("Past Directory Import Batches:");
        historyTitle.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");

        importHistoryTableView = new TableView<>();
        importHistoryTableView.setItems(importHistoryData);
        VBox.setVgrow(importHistoryTableView, Priority.ALWAYS);

        TableColumn<HsnImportHistoryItem, String> colDate = new TableColumn<>("Import Date");
        colDate.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().importedAt()));
        colDate.setPrefWidth(160);

        TableColumn<HsnImportHistoryItem, String> colFile = new TableColumn<>("File Name");
        colFile.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().fileName()));
        colFile.setPrefWidth(220);

        TableColumn<HsnImportHistoryItem, String> colTotal = new TableColumn<>("Total Rows");
        colTotal.setCellValueFactory(d -> new SimpleStringProperty(String.valueOf(d.getValue().totalRows())));
        colTotal.setPrefWidth(90);

        TableColumn<HsnImportHistoryItem, String> colImp = new TableColumn<>("Imported");
        colImp.setCellValueFactory(d -> new SimpleStringProperty(String.valueOf(d.getValue().importedCount())));
        colImp.setPrefWidth(90);

        TableColumn<HsnImportHistoryItem, String> colUpd = new TableColumn<>("Updated");
        colUpd.setCellValueFactory(d -> new SimpleStringProperty(String.valueOf(d.getValue().updatedCount())));
        colUpd.setPrefWidth(90);

        TableColumn<HsnImportHistoryItem, String> colStatus = new TableColumn<>("Status");
        colStatus.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().status()));
        colStatus.setPrefWidth(100);

        importHistoryTableView.getColumns().addAll(colDate, colFile, colTotal, colImp, colUpd, colStatus);
        layout.getChildren().addAll(importBar, new Separator(), historyTitle, importHistoryTableView);
        return layout;
    }

    private void startExcelImport() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Select Official HSN Directory Excel Spreadsheet");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Excel Spreadsheets (*.xlsx, *.xls)", "*.xlsx", "*.xls"));
        File file = chooser.showOpenDialog(screenManager.getStage());
        if (file == null) return;

        Stage progressStage = new Stage();
        progressStage.initModality(Modality.APPLICATION_MODAL);
        progressStage.initOwner(screenManager.getStage());
        progressStage.setTitle("Importing HSN Directory");

        ProgressBar progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(380);
        Label msgLabel = new Label("Reading Excel spreadsheet...");
        Button cancelBtn = new Button("Cancel");

        VBox box = new VBox(14, new Label("Importing Official HSN Directory: " + file.getName()), progressBar, msgLabel, cancelBtn);
        box.setPadding(new Insets(20));
        box.setAlignment(Pos.CENTER);
        progressStage.setScene(new javafx.scene.Scene(box, 440, 180));

        AtomicBoolean cancelled = new AtomicBoolean(false);
        cancelBtn.setOnAction(e -> {
            cancelled.set(true);
            msgLabel.setText("Cancelling import...");
            cancelBtn.setDisable(true);
        });

        Task<HsnImportResult> task = new Task<>() {
            @Override
            protected HsnImportResult call() {
                return importService.importExcel(
                        file,
                        (processed, total, msg) -> Platform.runLater(() -> {
                            double p = total > 0 ? (double) processed / total : -1;
                            progressBar.setProgress(p);
                            msgLabel.setText(msg);
                        }),
                        cancelled::get
                );
            }
        };

        task.setOnSucceeded(e -> {
            progressStage.close();
            HsnImportResult res = task.getValue();
            loadAllData();
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle("Import Complete");
            alert.setHeaderText("HSN Master Directory Updated Successfully");
            alert.setContentText(String.format("Processed: %d rows\nImported: %d\nUpdated: %d\nSkipped: %d\nDuration: %.2f sec",
                    res.totalRows(), res.importedCount(), res.updatedCount(), res.skippedCount(), res.durationMs() / 1000.0));
            alert.showAndWait();
        });

        task.setOnFailed(e -> {
            progressStage.close();
            Throwable ex = task.getException();
            new Alert(Alert.AlertType.ERROR, "Import Failed: " + (ex != null ? ex.getMessage() : "Unknown error"), ButtonType.OK).showAndWait();
        });

        new Thread(task).start();
        progressStage.showAndWait();
    }

    private void loadAllData() {
        searchDirectory();
        filterProducts();
        loadGroupsData();
        loadGstRatesData();
        loadAuditData();
        loadImportHistoryData();
    }

    private void loadGroupsData() {
        groupsData.setAll(groupService.getAllGroups(false));
    }

    private void loadGstRatesData() {
        gstRateData.setAll(gstRateService.getAllCurrentRates());
    }

    private void loadAuditData() {
        auditData.setAll(verificationService.getRecentAudits(50));
    }

    private void loadImportHistoryData() {
        importHistoryData.setAll(hsnService.getImportHistory(20));
    }
}
