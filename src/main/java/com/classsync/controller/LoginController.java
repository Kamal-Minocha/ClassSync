package com.classsync.controller;

import com.classsync.AppContext;
import com.classsync.model.User;
import com.classsync.service.AuthService;
import com.classsync.service.Errors;
import com.classsync.view.AppShell;
import com.classsync.view.Icons;
import com.classsync.view.Images;
import com.classsync.view.Ui;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * Controller for login.fxml (the FXML example in this project).
 * Handles UI events only; all rules live in AuthService, which runs on a background Task.
 */
public class LoginController {

    private final AppContext ctx;
    private final AppShell shell;

    @FXML private HBox root;
    @FXML private StackPane brandPanel;
    @FXML private HBox brandRow;
    @FXML private VBox stageCards;
    @FXML private VBox loginCard;
    @FXML private VBox registerCard;
    @FXML private TextField identifierField;
    @FXML private PasswordField passwordField;
    @FXML private TextField passwordVisibleField;
    @FXML private CheckBox showPasswordBox;
    @FXML private Label loginError;
    @FXML private Label loginInfo;
    @FXML private Button signInButton;

    @FXML private TextField regName;
    @FXML private TextField regEmail;
    @FXML private TextField regEnrollment;
    @FXML private ComboBox<Integer> regSemester;
    @FXML private TextField regDepartment;
    @FXML private PasswordField regPassword;
    @FXML private PasswordField regConfirm;
    @FXML private Label registerError;
    @FXML private Button registerButton;

    public LoginController(AppContext ctx, AppShell shell) {
        this.ctx = ctx;
        this.shell = shell;
    }

    /** Called by FXMLLoader after all @FXML fields are injected. */
    @FXML
    private void initialize() {
        // campus photo (Thapar mesh-facade buildings) under a maroon gradient
        brandPanel.getChildren().addAll(0, java.util.List.of(
                Ui.photoLayer(Images.CAMPUS_FACADE), Ui.overlay("login-overlay")));
        VBox name = new VBox(0, Ui.label("ClassSync", "h2"),
                Ui.label("Thapar Institute of Engineering & Technology", "brand-sub", "small"));
        brandRow.getChildren().addAll(Ui.logoMark(46), name);

        stageCards.getChildren().addAll(
                stageCard(Icons.BOOK_OPEN, "Before", "Upcoming topics, objectives and preparation material"),
                stageCard(Icons.QUIZ, "During", "Lecture slides, live quizzes, code attendance and doubts"),
                stageCard(Icons.REVISION, "After", "Feedback, approved notes, mistake review and revision"));

        // hide the brand panel on narrow windows so the form always fits
        root.widthProperty().addListener((obs, old, w) -> {
            boolean wide = w.doubleValue() >= 1000;
            brandPanel.setVisible(wide);
            brandPanel.setManaged(wide);
        });

        // show/hide password: two fields share the same text
        passwordVisibleField.textProperty().bindBidirectional(passwordField.textProperty());
        showPasswordBox.selectedProperty().addListener((obs, old, show) -> {
            passwordVisibleField.setVisible(show);
            passwordVisibleField.setManaged(show);
            passwordField.setVisible(!show);
            passwordField.setManaged(!show);
        });

        for (int s = 1; s <= 12; s++) {
            regSemester.getItems().add(s);
        }
        regSemester.getSelectionModel().select(Integer.valueOf(1));

        identifierField.textProperty().addListener((o, a, b) -> hide(loginError));
        passwordField.textProperty().addListener((o, a, b) -> hide(loginError));
        javafx.application.Platform.runLater(identifierField::requestFocus);
    }

    private HBox stageCard(String icon, String title, String text) {
        VBox words = new VBox(2, Ui.label(title, "body-strong"), Ui.wrapping(text, "brand-sub", "small"));
        HBox card = new HBox(12, Icons.of(icon, 22), words);
        card.getStyleClass().add("login-stage-card");
        card.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        return card;
    }

    @FXML
    private void onSignIn() {
        hide(loginInfo);
        String identifier = identifierField.getText();
        char[] password = passwordField.getText().toCharArray();
        if (identifier.isBlank() || password.length == 0) {
            show(loginError, "Enter your email or enrollment number and your password.");
            return;
        }
        setBusy(signInButton, true, "Signing in…");
        ctx.background().run(
                () -> ctx.auth().login(identifier, password),
                (User user) -> {
                    passwordField.clear();
                    shell.onSignedIn(user);
                },
                error -> {
                    setBusy(signInButton, false, "Sign in");
                    passwordField.clear();
                    show(loginError, Errors.userMessage(error));
                    passwordField.requestFocus();
                });
    }

    @FXML
    private void onRegister() {
        hide(registerError);
        AuthService.Registration form = new AuthService.Registration(
                regName.getText(), regEmail.getText(), regEnrollment.getText(), regDepartment.getText(),
                regSemester.getValue() == null ? 0 : regSemester.getValue(),
                regPassword.getText().toCharArray(), regConfirm.getText().toCharArray());
        setBusy(registerButton, true, "Creating account…");
        ctx.background().run(
                () -> ctx.auth().registerStudent(form),
                (User user) -> {
                    setBusy(registerButton, false, "Create account");
                    regPassword.clear();
                    regConfirm.clear();
                    identifierField.setText(user.email());
                    showLogin();
                    show(loginInfo, "Account created. Sign in with your new password.");
                    passwordField.requestFocus();
                },
                error -> {
                    setBusy(registerButton, false, "Create account");
                    show(registerError, Errors.userMessage(error));
                });
    }

    @FXML
    private void showRegister() {
        swap(registerCard, loginCard);
        regName.requestFocus();
    }

    @FXML
    private void showLogin() {
        swap(loginCard, registerCard);
    }

    private static void swap(VBox show, VBox hide) {
        hide.setVisible(false);
        hide.setManaged(false);
        show.setVisible(true);
        show.setManaged(true);
    }

    private static void setBusy(Button button, boolean busy, String text) {
        button.setDisable(busy);
        button.setText(text);
    }

    private static void show(Label label, String message) {
        label.setText(message);
        label.setVisible(true);
        label.setManaged(true);
    }

    private static void hide(Label label) {
        label.setVisible(false);
        label.setManaged(false);
    }
}
