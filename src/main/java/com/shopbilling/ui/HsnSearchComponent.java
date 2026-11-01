package com.shopbilling.ui;

import com.shopbilling.model.HsnItem;
import com.shopbilling.service.HsnService;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Reusable JavaFX Component providing live asynchronous HSN search,
 * auto-suggestion dropdown/ListView, selected classification badge,
 * loading indicator, clear button, and no-results message (Section 6).
 */
public class HsnSearchComponent extends VBox {

    private final HsnService hsnService;
    private final TextField searchField;
    private final ProgressIndicator loadingSpinner;
    private final ListView<HsnItem> suggestionListView;
    private final Label noResultsLabel;
    private final HBox selectedBadgeBox;
    private final Label selectedCodeLabel;
    private final Label selectedDescLabel;
    private final Button clearBtn;

    private HsnItem selectedHsn;
    private Consumer<HsnItem> onSelectionChanged;
    private Task<List<HsnItem>> activeSearchTask;

    public HsnSearchComponent(HsnService hsnService) {
        this(hsnService, null);
    }

    public HsnSearchComponent(HsnService hsnService, HsnItem initialSelection) {
        super(6);
        this.hsnService = Objects.requireNonNull(hsnService, "HsnService cannot be null");

        // 1. Search Bar with Loading Spinner
        HBox searchBar = new HBox(6);
        searchBar.setAlignment(Pos.CENTER_LEFT);

        searchField = new TextField();
        searchField.setPromptText("Type HSN code or item description to search (e.g. 8544, switch, LED)...");
        HBox.setHgrow(searchField, Priority.ALWAYS);

        loadingSpinner = new ProgressIndicator();
        loadingSpinner.setPrefSize(18, 18);
        loadingSpinner.setVisible(false);

        searchBar.getChildren().addAll(searchField, loadingSpinner);

        // 2. Auto-suggestion ListView
        ObservableList<HsnItem> suggestions = FXCollections.observableArrayList();
        suggestionListView = new ListView<>(suggestions);
        suggestionListView.setPrefHeight(130);
        suggestionListView.setVisible(false);
        suggestionListView.setManaged(false);
        suggestionListView.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(HsnItem item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    setText(String.format("[%s] %s (%s)", item.hsnCode(), item.description(), item.productType()));
                    setStyle("-fx-font-size: 11px; -fx-padding: 3 6;");
                }
            }
        });

        // 3. No Results Message
        noResultsLabel = new Label("No matching HSN classifications found in directory.");
        noResultsLabel.setStyle("-fx-text-fill: #dc2626; -fx-font-size: 11px; -fx-font-style: italic;");
        noResultsLabel.setVisible(false);
        noResultsLabel.setManaged(false);

        // 4. Selected Classification Badge Box
        selectedBadgeBox = new HBox(8);
        selectedBadgeBox.setAlignment(Pos.CENTER_LEFT);
        selectedBadgeBox.setPadding(new Insets(6, 10, 6, 10));
        selectedBadgeBox.setStyle("-fx-background-color: #f0fdf4; -fx-border-color: #86efac; -fx-border-radius: 4; -fx-background-radius: 4;");
        selectedBadgeBox.setVisible(false);
        selectedBadgeBox.setManaged(false);

        selectedCodeLabel = new Label();
        selectedCodeLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: #166534; -fx-font-size: 12px;");

        selectedDescLabel = new Label();
        selectedDescLabel.setStyle("-fx-text-fill: #15803d; -fx-font-size: 11px;");
        HBox.setHgrow(selectedDescLabel, Priority.ALWAYS);

        clearBtn = new Button("✕ Clear");
        clearBtn.setStyle("-fx-background-color: transparent; -fx-text-fill: #dc2626; -fx-font-weight: bold; -fx-cursor: hand;");
        clearBtn.setOnAction(e -> clearSelection());

        selectedBadgeBox.getChildren().addAll(selectedCodeLabel, selectedDescLabel, clearBtn);

        // Event Listeners
        searchField.textProperty().addListener((obs, oldVal, newVal) -> triggerSearch(newVal));

        suggestionListView.getSelectionModel().selectedItemProperty().addListener((obs, oldItem, newItem) -> {
            if (newItem != null) {
                selectHsn(newItem);
                suggestionListView.setVisible(false);
                suggestionListView.setManaged(false);
            }
        });

        getChildren().addAll(searchBar, selectedBadgeBox, noResultsLabel, suggestionListView);

        if (initialSelection != null) {
            selectHsn(initialSelection);
        }
    }

    public void setOnSelectionChanged(Consumer<HsnItem> onSelectionChanged) {
        this.onSelectionChanged = onSelectionChanged;
    }

    public HsnItem getSelectedHsn() {
        return selectedHsn;
    }

    public String getSelectedHsnCode() {
        return selectedHsn != null ? selectedHsn.hsnCode() : null;
    }

    public void selectHsn(HsnItem item) {
        this.selectedHsn = item;
        if (item != null) {
            selectedCodeLabel.setText("Selected HSN: " + item.hsnCode());
            selectedDescLabel.setText(item.description());
            selectedBadgeBox.setVisible(true);
            selectedBadgeBox.setManaged(true);
            noResultsLabel.setVisible(false);
            noResultsLabel.setManaged(false);
            suggestionListView.setVisible(false);
            suggestionListView.setManaged(false);
            searchField.setText("");
        } else {
            selectedBadgeBox.setVisible(false);
            selectedBadgeBox.setManaged(false);
        }

        if (onSelectionChanged != null) {
            onSelectionChanged.accept(selectedHsn);
        }
    }

    public void selectByCode(String code) {
        if (code == null || code.trim().isEmpty()) {
            clearSelection();
            return;
        }
        hsnService.getHsnByCode(code).ifPresentOrElse(
                this::selectHsn,
                () -> selectHsn(new HsnItem(code.trim(), "Custom/Unindexed Classification", "GOODS"))
        );
    }

    public void clearSelection() {
        selectHsn(null);
    }

    private void triggerSearch(String query) {
        if (query == null || query.trim().length() < 2) {
            suggestionListView.getItems().clear();
            suggestionListView.setVisible(false);
            suggestionListView.setManaged(false);
            noResultsLabel.setVisible(false);
            noResultsLabel.setManaged(false);
            loadingSpinner.setVisible(false);
            return;
        }

        if (activeSearchTask != null && activeSearchTask.isRunning()) {
            activeSearchTask.cancel();
        }

        loadingSpinner.setVisible(true);
        noResultsLabel.setVisible(false);
        noResultsLabel.setManaged(false);

        activeSearchTask = new Task<>() {
            @Override
            protected List<HsnItem> call() {
                return hsnService.searchHsn(query.trim(), 20);
            }
        };

        activeSearchTask.setOnSucceeded(e -> {
            loadingSpinner.setVisible(false);
            List<HsnItem> results = activeSearchTask.getValue();
            if (results.isEmpty()) {
                suggestionListView.getItems().clear();
                suggestionListView.setVisible(false);
                suggestionListView.setManaged(false);
                noResultsLabel.setVisible(true);
                noResultsLabel.setManaged(true);
            } else {
                suggestionListView.getItems().setAll(results);
                suggestionListView.setVisible(true);
                suggestionListView.setManaged(true);
                noResultsLabel.setVisible(false);
                noResultsLabel.setManaged(false);
            }
        });

        activeSearchTask.setOnFailed(e -> {
            loadingSpinner.setVisible(false);
            noResultsLabel.setText("Search error: " + activeSearchTask.getException().getMessage());
            noResultsLabel.setVisible(true);
            noResultsLabel.setManaged(true);
        });

        Thread th = new Thread(activeSearchTask, "HsnLiveSearchThread");
        th.setDaemon(true);
        th.start();
    }
}
