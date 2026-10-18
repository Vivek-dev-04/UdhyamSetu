package com.shopbilling.ui;

import com.shopbilling.model.User;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

import java.util.Objects;

/**
 * Screen handling user authentication and first-launch owner account setup (FR-41, FR-42).
 */
public class LoginScreen {

    private final ScreenManager screenManager;
    private final AppContext context;

    public LoginScreen(ScreenManager screenManager) {
        this.screenManager = Objects.requireNonNull(screenManager, "ScreenManager cannot be null");
        this.context = screenManager.getContext();
    }

    public Parent getView() {
        boolean firstLaunch = context.getAuthService().isFirstLaunch();

        VBox root = new VBox(20);
        root.setAlignment(Pos.CENTER);
        root.setPadding(new Insets(40));
        root.setStyle("-fx-background-color: #f1f5f9;");

        VBox card = new VBox(15);
        card.setMaxWidth(460);
        card.setPadding(new Insets(30));
        card.setStyle("-fx-background-color: #ffffff; -fx-background-radius: 10px; " +
                "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.1), 15, 0, 0, 5); " +
                "-fx-border-color: #cbd5e1; -fx-border-radius: 10px;");

        Label appTitle = new Label("UdhyamSetu");
        appTitle.setStyle("-fx-font-size: 26px; -fx-font-weight: bold; -fx-text-fill: #1e293b;");

        Label subtitle = new Label(firstLaunch
                ? "First Launch • Create Shop Owner Account"
                : "Single Electrical Goods Shop • Offline System");
        subtitle.setStyle("-fx-font-size: 13px; -fx-text-fill: #64748b;");

        Label errorLabel = new Label();
        errorLabel.setStyle("-fx-text-fill: #dc2626; -fx-font-size: 13px;");
        errorLabel.setWrapText(true);
        errorLabel.setVisible(false);

        GridPane form = new GridPane();
        form.setHgap(10);
        form.setVgap(12);

        if (firstLaunch) {
            TextField fullNameField = new TextField();
            fullNameField.setPromptText("e.g. Ramesh Kumar");

            TextField usernameField = new TextField();
            usernameField.setPromptText("e.g. owner");

            PasswordField passwordField = new PasswordField();
            passwordField.setPromptText("Min 6 characters");

            PasswordField confirmPasswordField = new PasswordField();
            confirmPasswordField.setPromptText("Re-type password");

            form.add(new Label("Full Name:"), 0, 0);
            form.add(fullNameField, 1, 0);
            form.add(new Label("Username:"), 0, 1);
            form.add(usernameField, 1, 1);
            form.add(new Label("Password:"), 0, 2);
            form.add(passwordField, 1, 2);
            form.add(new Label("Confirm Password:"), 0, 3);
            form.add(confirmPasswordField, 1, 3);

            Button setupBtn = new Button("Create Owner Account");
            setupBtn.setMaxWidth(Double.MAX_VALUE);
            setupBtn.setStyle("-fx-background-color: #2563eb; -fx-text-fill: white; " +
                    "-fx-font-weight: bold; -fx-font-size: 14px; -fx-padding: 10 20; -fx-background-radius: 6px;");

            setupBtn.setOnAction(e -> {
                String fullName = fullNameField.getText();
                String username = usernameField.getText();
                String pwd = passwordField.getText();
                String confirmPwd = confirmPasswordField.getText();

                if (!pwd.equals(confirmPwd)) {
                    errorLabel.setText("Passwords do not match");
                    errorLabel.setVisible(true);
                    return;
                }

                try {
                    User owner = context.getAuthService().setupInitialOwner(username, pwd, fullName);
                    context.setCurrentUser(owner);
                    screenManager.showHome();
                } catch (Exception ex) {
                    errorLabel.setText(ex.getMessage());
                    errorLabel.setVisible(true);
                }
            });

            card.getChildren().addAll(appTitle, subtitle, new Separator(), errorLabel, form, setupBtn);

        } else {
            TextField usernameField = new TextField();
            usernameField.setPromptText("Enter username");

            PasswordField passwordField = new PasswordField();
            passwordField.setPromptText("Enter password");

            form.add(new Label("Username:"), 0, 0);
            form.add(usernameField, 1, 0);
            form.add(new Label("Password:"), 0, 1);
            form.add(passwordField, 1, 1);

            Button loginBtn = new Button("Log In");
            loginBtn.setMaxWidth(Double.MAX_VALUE);
            loginBtn.setStyle("-fx-background-color: #2563eb; -fx-text-fill: white; " +
                    "-fx-font-weight: bold; -fx-font-size: 14px; -fx-padding: 10 20; -fx-background-radius: 6px;");

            Runnable doLogin = () -> {
                try {
                    User user = context.getAuthService().authenticate(usernameField.getText(), passwordField.getText());
                    context.setCurrentUser(user);
                    screenManager.showHome();
                } catch (Exception ex) {
                    errorLabel.setText(ex.getMessage());
                    errorLabel.setVisible(true);
                }
            };

            loginBtn.setOnAction(e -> doLogin.run());
            passwordField.setOnAction(e -> doLogin.run());

            card.getChildren().addAll(appTitle, subtitle, new Separator(), errorLabel, form, loginBtn);
        }

        root.getChildren().add(card);
        return root;
    }
}
