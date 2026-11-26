package com.classsync.view;

import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/** Screens shown before login: readiness check, connection failure, outdated schema. */
public final class StartupView {

    private StartupView() {
    }

    public static Parent checking() {
        StackPane root = new StackPane(Ui.loading("Connecting to the ClassSync database…"));
        root.getStyleClass().add("login-form-wrap");
        return root;
    }

    public static Parent connectionFailed(String message, String jdbcUrl, Runnable retry) {
        Button again = Ui.primary("Retry connection", Icons.REFRESH);
        again.setOnAction(e -> retry.run());
        Label steps = Ui.wrapping("""
                1. Start Docker Desktop.
                2. In the ClassSync folder run:  docker compose up -d postgres
                3. Wait until it is healthy:     docker compose ps
                4. New database? Create the tables:
                     mvnw compile exec:java@migrate
                """.strip(), "muted", "small");
        steps.setStyle("-fx-font-family: 'Consolas';");
        return centered(Icons.DATABASE, "Cannot connect to the database",
                message + "\nDatabase: " + jdbcUrl, steps, again);
    }

    public static Parent schemaOutdated(int current, int required, Runnable retry) {
        Button again = Ui.primary("Check again", Icons.REFRESH);
        again.setOnAction(e -> retry.run());
        Label steps = Ui.wrapping("Run the migration tool as the migration owner, then check again:\n"
                + "    mvnw compile exec:java@migrate", "muted", "small");
        steps.setStyle("-fx-font-family: 'Consolas';");
        return centered(Icons.DATABASE, "Database schema needs an update",
                "This version of ClassSync needs schema version " + required
                        + ", but the database is at version " + current + ". Your data is kept.", steps, again);
    }

    public static Parent configurationError(String message) {
        return centered(Icons.SETTINGS, "Configuration problem", message,
                Ui.wrapping("Copy .env.example to .env and fill in the passwords, then restart ClassSync.", "muted"),
                null);
    }

    private static Parent centered(String icon, String title, String message, Label steps, Button action) {
        VBox card = Ui.card();
        card.setMaxWidth(560);
        card.setMaxHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        card.setSpacing(14);
        card.getChildren().addAll(Icons.of(icon, 36), Ui.label(title, "h2"), Ui.wrapping(message, "muted"), steps);
        if (action != null) {
            card.getChildren().add(action);
        }
        // faded main-building photo behind the card
        StackPane padded = new StackPane(card);
        padded.getStyleClass().add("login-form-wrap");
        padded.setStyle("-fx-background-color: transparent;");
        StackPane root = new StackPane(Ui.photoLayer(Images.CAMPUS_MAIN), Ui.overlay("startup-scrim"), padded);
        StackPane.setAlignment(card, Pos.CENTER);
        return root;
    }
}
