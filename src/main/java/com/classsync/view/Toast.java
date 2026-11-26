package com.classsync.view;

import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.animation.SequentialTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/** Small success/error/info notices shown in the bottom-right corner of the window. */
public class Toast {

    private final VBox stack = new VBox(10);

    public Toast(StackPane host) {
        stack.setAlignment(Pos.BOTTOM_RIGHT);
        stack.setPickOnBounds(false);
        stack.setPadding(new Insets(20));
        stack.setMouseTransparent(false);
        StackPane.setAlignment(stack, Pos.BOTTOM_RIGHT);
        stack.setMaxSize(460, javafx.scene.layout.Region.USE_COMPUTED_SIZE);
        host.getChildren().add(stack);
    }

    public void success(String message) {
        show(message, "toast-success", Icons.CHECK);
    }

    public void error(String message) {
        show(message, "toast-error", Icons.ALERT);
    }

    public void info(String message) {
        show(message, "toast-info", Icons.BELL);
    }

    private void show(String message, String style, String icon) {
        Label text = Ui.wrapping(message);
        text.setMaxWidth(340);
        HBox box = new HBox(10, Icons.of(icon, 18), text);
        box.setAlignment(Pos.CENTER_LEFT);
        box.getStyleClass().addAll("toast", style);
        box.setAccessibleText(message);
        box.setOnMouseClicked(e -> stack.getChildren().remove(box));
        stack.getChildren().add(box);

        // the notice is removed after a pause; the fade is only decoration
        FadeTransition in = new FadeTransition(Duration.millis(150), box);
        in.setFromValue(0);
        in.setToValue(1);
        PauseTransition wait = new PauseTransition(Duration.seconds(style.equals("toast-error") ? 6 : 3.5));
        FadeTransition out = new FadeTransition(Duration.millis(250), box);
        out.setToValue(0);
        SequentialTransition seq = new SequentialTransition(in, wait, out);
        seq.setOnFinished(e -> stack.getChildren().remove(box));
        seq.play();
    }
}
