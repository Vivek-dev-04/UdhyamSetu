package com.shopbilling.ui;

import com.shopbilling.model.SaleMode;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.util.Objects;

/**
 * Stage navigator managing scene switching between application screens.
 */
public class ScreenManager {

    private final Stage stage;
    private final AppContext context;

    public ScreenManager(Stage stage, AppContext context) {
        this.stage = Objects.requireNonNull(stage, "Stage cannot be null");
        this.context = Objects.requireNonNull(context, "AppContext cannot be null");
    }

    public Stage getStage() {
        return stage;
    }

    public AppContext getContext() {
        return context;
    }

    public void showLogin() {
        LoginScreen screen = new LoginScreen(this);
        setScene(screen.getView(), 650, 520, "UdhyamSetu - Login");
    }

    public void showHome() {
        HomeScreen screen = new HomeScreen(this);
        setScene(screen.getView(), 1024, 700, "UdhyamSetu - Electrical Shop Billing System");
    }

    public void showBilling(SaleMode mode) {
        BillingScreen screen = new BillingScreen(this, mode);
        setScene(screen.getView(), 1100, 750, "UdhyamSetu - " + (mode == SaleMode.BILL ? "Generate Bill" : "Register Sale"));
    }

    public void showSales() {
        SalesScreen screen = new SalesScreen(this);
        setScene(screen.getView(), 1100, 750, "UdhyamSetu - Sales & Returns");
    }

    public void showStock() {
        StockScreen screen = new StockScreen(this);
        setScene(screen.getView(), 1050, 720, "UdhyamSetu - Stock & Purchases");
    }

    public void showCreditLedger() {
        CreditLedgerScreen screen = new CreditLedgerScreen(this);
        setScene(screen.getView(), 1050, 720, "UdhyamSetu - Customer & Supplier Ledgers");
    }

    public void showExpensesAndClosing() {
        ExpenseAndClosingScreen screen = new ExpenseAndClosingScreen(this);
        setScene(screen.getView(), 1050, 720, "UdhyamSetu - Expenses & Day-End Closing");
    }

    public void showReportsAndProfit() {
        ReportsAndProfitScreen screen = new ReportsAndProfitScreen(this);
        setScene(screen.getView(), 1100, 750, "UdhyamSetu - Reports & Profit");
    }

    public void showSettingsAndAdmin() {
        SettingsAndAdminScreen screen = new SettingsAndAdminScreen(this);
        setScene(screen.getView(), 1050, 720, "UdhyamSetu - Settings & Admin");
    }

    private void setScene(Parent root, double width, double height, String title) {
        Scene scene = stage.getScene();
        if (scene == null) {
            scene = new Scene(root, width, height);
            stage.setScene(scene);
        } else {
            scene.setRoot(root);
        }
        stage.setTitle(title);
        stage.show();
    }
}
