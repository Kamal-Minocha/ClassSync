package com.classsync.view;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.Optional;
import java.util.function.Supplier;

/** Alert and Dialog helpers that follow the current theme. */
public final class Dialogs {

    private Dialogs() {
    }

    public static boolean confirm(Window owner, String title, String message, String okText) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle(title);
        alert.setHeaderText(title);
        alert.setContentText(message);
        ButtonType ok = new ButtonType(okText, ButtonBar.ButtonData.OK_DONE);
        alert.getButtonTypes().setAll(ButtonType.CANCEL, ok);
        style(alert, owner);
        alert.getDialogPane().lookupButton(ok).getStyleClass().add("btn-primary");
        return alert.showAndWait().filter(b -> b == ok).isPresent();
    }

    public static void info(Window owner, String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION, message, ButtonType.OK);
        alert.setTitle(title);
        alert.setHeaderText(title);
        style(alert, owner);
        alert.showAndWait();
    }

    /** Asks for a required reason (e.g. note rejection, attendance correction). */
    public static Optional<String> askText(Window owner, String title, String prompt, int maxLength) {
        TextArea area = new TextArea();
        area.setPromptText(prompt);
        area.setWrapText(true);
        area.setPrefRowCount(4);
        Label error = Ui.label("", "field-error");
        Dialog<String> d = new Dialog<>();
        d.setTitle(title);
        d.setHeaderText(title);
        ButtonType ok = new ButtonType("Submit", ButtonBar.ButtonData.OK_DONE);
        d.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, ok);
        d.getDialogPane().setContent(new VBox(8, Ui.label(prompt, "muted"), area, error));
        style(d, owner);
        Node okButton = d.getDialogPane().lookupButton(ok);
        okButton.getStyleClass().add("btn-primary");
        okButton.addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
            String t = area.getText().trim();
            if (t.isEmpty() || t.length() > maxLength) {
                error.setText(t.isEmpty() ? "This field is required." : "At most " + maxLength + " characters.");
                e.consume();
            }
        });
        d.setResultConverter(b -> b == ok ? area.getText().trim() : null);
        javafx.application.Platform.runLater(area::requestFocus);
        return d.showAndWait();
    }

    /**
     * Form dialog: validator returns an error message (or null when valid); the dialog stays
     * open while the form is invalid.
     */
    public static boolean form(Window owner, String title, Node content, String okText, Supplier<String> validator) {
        Dialog<ButtonType> d = new Dialog<>();
        d.setTitle(title);
        d.setHeaderText(title);
        ButtonType ok = new ButtonType(okText, ButtonBar.ButtonData.OK_DONE);
        d.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, ok);
        Label error = Ui.label("", "field-error");
        error.setWrapText(true);
        d.getDialogPane().setContent(new VBox(12, content, error));
        d.getDialogPane().setPrefWidth(560);
        style(d, owner);
        Node okButton = d.getDialogPane().lookupButton(ok);
        okButton.getStyleClass().add("btn-primary");
        okButton.addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
            String problem = validator.get();
            if (problem != null) {
                error.setText(problem);
                e.consume();
            }
        });
        return d.showAndWait().filter(b -> b == ok).isPresent();
    }

    private static void style(Dialog<?> dialog, Window owner) {
        if (owner != null) {
            dialog.initOwner(owner);
            Scene scene = owner.getScene();
            if (scene != null) {
                dialog.getDialogPane().getStylesheets().setAll(scene.getStylesheets());
            }
        }
        dialog.setResizable(true);
    }
}
