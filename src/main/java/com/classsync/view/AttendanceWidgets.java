package com.classsync.view;

import com.classsync.AppContext;
import com.classsync.model.AttendanceSession;
import com.classsync.model.AttendanceStatus;
import com.classsync.model.User;
import com.classsync.service.AttendanceService;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.OptionalDouble;

/** Attendance pieces shared by the Attendance page, the lecture tab and the dashboard. */
final class AttendanceWidgets {

    private AttendanceWidgets() {
    }

    static Label statusBadge(AttendanceStatus s) {
        if (s == null) {
            return Ui.badge("Not marked", "neutral");
        }
        return Ui.badge(s.label(), s == AttendanceStatus.PRESENT ? "success" : "danger");
    }

    /** "83%" or "—" when there is nothing to count yet. */
    static String percent(OptionalDouble f) {
        return f.isPresent() ? Ui.percent(f.getAsDouble()) : "—";
    }

    /** Course line: code, progress bar, present/counted and a warning badge below the threshold. */
    static HBox courseRow(String courseCode, String name, int present, int counted) {
        OptionalDouble f = AttendanceService.fraction(present, counted);
        boolean low = AttendanceService.isLow(present, counted);
        ProgressBar bar = Ui.progress(f.orElse(0), low ? "progress-danger" : null);
        bar.setPrefWidth(180);
        VBox text = new VBox(2, Ui.label(courseCode + (name == null ? "" : " · " + name), "body-strong"),
                Ui.label(counted == 0 ? "No finalized lectures yet" : present + " of " + QuizWidgets.plural(counted, "lecture"), "small", "muted"));
        HBox.setHgrow(text, Priority.ALWAYS);
        text.setMinWidth(0);
        HBox row = Ui.row(12, text, bar, Ui.label(percent(f), "body-strong"));
        if (low) {
            row.getChildren().add(Ui.badge("Below " + Ui.percent(AttendanceService.LOW_ATTENDANCE), "danger"));
        }
        row.getStyleClass().add("list-row");
        return row;
    }

    /** Code input for one open session; Enter or the button submits. */
    static HBox codeEntry(AppContext ctx, AppShell shell, AttendanceSession s, Runnable onMarked) {
        User u = ctx.session().requireUser();
        TextField code = new TextField();
        code.setPromptText("6-character code");
        code.setPrefColumnCount(10);
        code.setAccessibleText("Attendance code for " + s.courseCode() + " lecture " + s.lectureNo());
        code.textProperty().addListener((o, a, b) -> {
            if (b != null && !b.equals(b.toUpperCase())) {
                code.setText(b.toUpperCase());
            }
        });
        Button mark = Ui.primary("Mark me present", Icons.CHECK);
        Runnable submit = () -> {
            if (code.getText().isBlank()) {
                shell.toast().error("Enter the code shown by your professor.");
                return;
            }
            mark.setDisable(true);
            String typed = code.getText();
            ctx.background().run(() -> {
                ctx.attendance().submitCode(u, s.id(), typed);
                return null;
            }, r -> {
                shell.toast().success("Marked present for " + s.courseCode() + " lecture " + s.lectureNo() + ".");
                onMarked.run();
            }, e -> {
                mark.setDisable(false);
                code.selectAll();
                code.requestFocus();
                shell.showError(e);
            });
        };
        mark.setOnAction(e -> submit.run());
        code.setOnAction(e -> submit.run());
        VBox text = new VBox(2, Ui.label(s.courseCode() + " · L" + s.lectureNo() + " · " + s.topic(), "body-strong"),
                Ui.label("Attendance is open — type the code on the classroom screen", "small", "muted"));
        HBox.setHgrow(text, Priority.ALWAYS);
        text.setMinWidth(0);
        HBox row = new HBox(12, Icons.of(Icons.ATTENDANCE, 20), text, code, mark);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("list-row");
        return row;
    }
}
