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
        setScene(screen.getView(), 1100, 750, "UdhyamSetu - Electrical Shop Billing System");
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
        showStock(0, false);
    }

    public void showStock(int initialTabIndex) {
        showStock(initialTabIndex, false);
    }

    public void showStock(int initialTabIndex, boolean openStockInImmediately) {
        StockScreen screen = new StockScreen(this, initialTabIndex, openStockInImmediately);
        setScene(screen.getView(), 1100, 750, "UdhyamSetu - Stock & Purchases");
    }

    public void showCustomerDues() {
        CreditLedgerScreen screen = new CreditLedgerScreen(this, CreditLedgerScreen.Mode.CUSTOMER_ONLY);
        setScene(screen.getView(), 1080, 720, "UdhyamSetu - Customer Receivables & Aging");
    }

    public void showSupplierPayables() {
        CreditLedgerScreen screen = new CreditLedgerScreen(this, CreditLedgerScreen.Mode.SUPPLIER_ONLY);
        setScene(screen.getView(), 1080, 720, "UdhyamSetu - Supplier Payables & Distributor Ledgers");
    }

    public void showCreditLedger() {
        showCreditLedger(0);
    }

    public void showCreditLedger(int initialTabIndex) {
        CreditLedgerScreen.Mode mode = (initialTabIndex == 1)
                ? CreditLedgerScreen.Mode.SUPPLIER_ONLY
                : CreditLedgerScreen.Mode.CUSTOMER_ONLY;
        CreditLedgerScreen screen = new CreditLedgerScreen(this, mode, initialTabIndex);
        String title = (mode == CreditLedgerScreen.Mode.SUPPLIER_ONLY)
                ? "UdhyamSetu - Supplier Payables & Distributor Ledgers"
                : "UdhyamSetu - Customer Receivables & Aging";
        setScene(screen.getView(), 1080, 720, title);
    }

    public void showExpenses() {
        ExpenseAndClosingScreen screen = new ExpenseAndClosingScreen(this, ExpenseAndClosingScreen.Mode.EXPENSE_ONLY);
        setScene(screen.getView(), 1050, 720, "UdhyamSetu - Daily Shop Expenses");
    }

    public void showDayEndClosing() {
        ExpenseAndClosingScreen screen = new ExpenseAndClosingScreen(this, ExpenseAndClosingScreen.Mode.CLOSING_ONLY);
        setScene(screen.getView(), 1050, 720, "UdhyamSetu - Day-End Cash Closing");
    }

    public void showExpensesAndClosing() {
        showExpensesAndClosing(0);
    }

    public void showExpensesAndClosing(int initialTabIndex) {
        ExpenseAndClosingScreen.Mode mode = (initialTabIndex == 1)
                ? ExpenseAndClosingScreen.Mode.CLOSING_ONLY
                : ExpenseAndClosingScreen.Mode.EXPENSE_ONLY;
        ExpenseAndClosingScreen screen = new ExpenseAndClosingScreen(this, mode, initialTabIndex);
        String title = (mode == ExpenseAndClosingScreen.Mode.CLOSING_ONLY)
                ? "UdhyamSetu - Day-End Cash Closing"
                : "UdhyamSetu - Daily Shop Expenses";
        setScene(screen.getView(), 1050, 720, title);
    }

    public void showReportsAndProfit() {
        ReportsAndProfitScreen screen = new ReportsAndProfitScreen(this);
        setScene(screen.getView(), 1100, 750, "UdhyamSetu - Reports & Profit");
    }

    public void showSettingsAndAdmin() {
        SettingsAndAdminScreen screen = new SettingsAndAdminScreen(this);
        setScene(screen.getView(), 1050, 720, "UdhyamSetu - Settings & Admin");
    }

    public void showHsnManagement() {
        HsnManagementScreen screen = new HsnManagementScreen(this);
        setScene(screen.getView(), 1100, 750, "UdhyamSetu - GST HSN Master Directory Management");
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
