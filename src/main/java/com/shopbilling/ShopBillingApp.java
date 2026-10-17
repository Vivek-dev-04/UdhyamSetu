package com.shopbilling;

import javafx.application.Application;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main JavaFX application entry point for the Shop Billing Desktop App.
 */
public class ShopBillingApp extends Application {

    private static final Logger logger = LoggerFactory.getLogger(ShopBillingApp.class);

    @Override
    public void start(Stage primaryStage) {
        logger.info("Initializing Shop Billing Desktop App UI...");
        
        javafx.scene.control.Label titleLabel = new javafx.scene.control.Label("Shop Billing Desktop App");
        titleLabel.setStyle("-fx-font-size: 20px; -fx-font-weight: bold;");

        javafx.scene.control.Label statusLabel = new javafx.scene.control.Label("M01: Project Skeleton Initialized\n(Desktop UI screens will be wired in M27-M37)");
        statusLabel.setStyle("-fx-font-size: 14px; -fx-text-alignment: center; -fx-text-fill: #555555;");

        javafx.scene.layout.VBox root = new javafx.scene.layout.VBox(15, titleLabel, statusLabel);
        root.setAlignment(javafx.geometry.Pos.CENTER);
        root.setStyle("-fx-padding: 30; -fx-background-color: #f8f9fa;");

        javafx.scene.Scene scene = new javafx.scene.Scene(root, 520, 320);
        primaryStage.setTitle("Shop Billing Desktop App");
        primaryStage.setScene(scene);
        primaryStage.show();

        logger.info("Main stage displayed.");
    }

    public static void main(String[] args) {
        logger.info("Starting Shop Billing Desktop App...");
        launch(args);
    }
}
