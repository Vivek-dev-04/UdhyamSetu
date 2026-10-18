package com.shopbilling;

import com.shopbilling.db.DataDirectoryHandler;
import com.shopbilling.ui.AppContext;
import com.shopbilling.ui.ScreenManager;
import javafx.application.Application;
import javafx.scene.image.Image;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main Application Entry Point for UdhyamSetu (Shop Billing Desktop App).
 * Single electrical goods shop offline billing, inventory, ledger, and reporting desktop system.
 */
public class ShopBillingApp extends Application {

    private static final Logger logger = LoggerFactory.getLogger(ShopBillingApp.class);

    private AppContext appContext;
    private ScreenManager screenManager;

    @Override
    public void init() throws Exception {
        logger.info("Initializing application data directories and core database services...");
        DataDirectoryHandler dataDirHandler = new DataDirectoryHandler();
        dataDirHandler.ensureDirectoriesExist();

        this.appContext = new AppContext(dataDirHandler);
    }

    @Override
    public void start(Stage primaryStage) {
        logger.info("Starting JavaFX UI shell...");
        this.screenManager = new ScreenManager(primaryStage, appContext);

        // Always check authentication or first-launch setup
        screenManager.showLogin();
    }

    public static void main(String[] args) {
        logger.info("Launching UdhyamSetu Application...");
        launch(args);
    }
}
