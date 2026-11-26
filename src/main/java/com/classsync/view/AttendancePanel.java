package com.classsync.view;

import com.classsync.AppContext;
import com.classsync.model.AttendanceCorrection;
import com.classsync.model.AttendanceRecord;
import com.classsync.model.AttendanceStatus;
import com.classsync.model.Lecture;
import com.classsync.model.LectureStatus;
import com.classsync.model.Role;
import com.classsync.model.User;
import com.classsync.service.AttendanceService.LectureAttendance;
import com.classsync.service.AttendanceService.LiveCode;
import com.classsync.service.Errors;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.time.Instant;

/**
 * The "Attendance" tab of a lecture.
 *
 * Professor: start a session, show the big code (renewed automatically when it expires), watch the
 * present count update, close or finalize, then correct individual records with a reason.
 * Student / CR: their status for the lecture, or a code box while a session is open.
 */
final class AttendancePanel {

    private final AppContext ctx;
    private final AppShell shell;
    private final User user;
    private final Lecture lecture;
    private final VBox root = new VBox(16);

    // live code state (professor only; the code exists only in this window's memory)
    private LiveCode live;
    private Instant localExpiry;
    private boolean renewing;
    private Timeline ticker;
    private Timeline poller;
    private final Label codeLabel = Ui.label("", "code-display");
    private final Label expiry = Ui.label("", "small", "muted");
    private final ProgressBar expiryBar = Ui.progress(1, null);
    private final Label presentCount = Ui.label("", "h2");

    private AttendancePanel(AppContext ctx, AppShell shell, User user, Lecture lecture) {
        this.ctx = ctx;
        this.shell = shell;
        this.user = user;
        this.lecture = lecture;
        root.sceneProperty().addListener((o, a, scene) -> {
            if (scene == null) {
                stopTimers();
            }
        });
    }

    static Node build(AppContext ctx, AppShell shell, User u, Lecture lecture) {
        AttendancePanel p = new AttendancePanel(ctx, shell, u, lecture);
        p.reload();
        return p.root;
    }

    private void reload() {
        root.getChildren().setAll(Ui.loading("Loading attendance…"));
        if (user.role() == Role.PROFESSOR) {
            ctx.background().run(() -> ctx.attendance().lecture(user, lecture.id()), this::showProfessor,
                    e -> root.getChildren().setAll(Ui.errorState(Errors.userMessage(e), this::reload)));
        } else {
            ctx.background().run(() -> ctx.attendance().myLecture(user, lecture.id()), my -> {
                root.getChildren().clear();
                if (my.status() != null) {
                    root.getChildren().add(Ui.card("Your attendance", Ui.row(10, AttendanceWidgets.statusBadge(my.status()),
                            Ui.label(my.finalized() ? "Final record for this lecture." : "Recorded. Your professor will finalize attendance.", "muted"))));
                } else if (my.openSession() != null) {
                    root.getChildren().add(Ui.card("Mark your attendance",
                            AttendanceWidgets.codeEntry(ctx, shell, my.openSession(), this::reload)));
                } else {
                    root.getChildren().add(Ui.emptyState(Icons.ATTENDANCE,
                            my.finalized() ? "Attendance is finalized" : "No attendance session is open",
                            my.finalized() ? "You have no record for this lecture (you may have enrolled later)."
                                    : "When your professor starts attendance, a code box appears here.", null));
                }
            }, e -> root.getChildren().setAll(Ui.errorState(Errors.userMessage(e), this::reload)));
        }
    }

    // ---------------------------------------------------------------- professor

    private void showProfessor(LectureAttendance a) {
        stopTimers();
        root.getChildren().clear();
        Lecture l = a.lecture();
        if (l.attendanceFinalized()) {
            long present = a.present();
            long absent = a.records().stream().filter(r -> r.status() == AttendanceStatus.ABSENT).count();
            root.getChildren().add(Ui.card(Ui.row(12, Ui.badge("Finalized", "success"),
                    Ui.label("Finalized " + l.attendanceFinalizedAt().atZone(ctx.lectures().zone()).format(Ui.DATE_TIME), "muted"),
                    Ui.spacer(), Ui.label(present + " present · " + absent + " absent", "body-strong"))));
            root.getChildren().add(Ui.card("Attendance record", roster(a, true)));
            root.getChildren().add(correctionsCard(a));
            return;
        }
        boolean allowed = l.status() == LectureStatus.PUBLISHED || l.status() == LectureStatus.COMPLETED;
        if (!allowed) {
            root.getChildren().add(Ui.emptyState(Icons.ATTENDANCE, "Attendance is not available",
                    "Attendance can be taken for published or completed lectures.", null));
            return;
        }
        Button finalize = Ui.button("Finalize attendance", Icons.CHECK);
        finalize.setOnAction(e -> confirmFinalize(a));
        finalize.setDisable(l.startsAt().isAfter(ctx.clock().instant()));

        VBox session;
        if (live != null && a.openSession() != null && a.openSession().id() == live.sessionId()) {
            session = liveCard(finalize);
        } else {
            live = null;
            Button start = Ui.primary(a.openSession() == null ? "Start attendance" : "Show a new code", Icons.ATTENDANCE);
            start.setOnAction(e -> act(() -> ctx.attendance().start(user, lecture.id())));
            String hint = a.openSession() == null
                    ? "Students in class type a 6-character code that changes every " + ctx.attendance().codeSeconds()
                      + " seconds. Codes discourage casual misuse but cannot prove presence — review the list before finalizing."
                    : "A session is open, but its code is not shown on this computer. Show a new code to continue; the old code stops working.";
            session = Ui.card("Take attendance", Ui.wrapping(hint, "muted"), Ui.row(8, start, finalize));
        }
        root.getChildren().addAll(session, Ui.card("Students (" + a.records().size() + ")", roster(a, false)));
        presentCount.setText(a.present() + " / " + a.records().size() + " present");
    }

    private VBox liveCard(Button finalize) {
        codeLabel.setText(spaced(live.code()));
        codeLabel.setAccessibleText("Attendance code " + String.join(" ", live.code().split("")));
        Button renew = Ui.button("New code", Icons.REFRESH);
        renew.setOnAction(e -> act(() -> ctx.attendance().newCode(user, live.sessionId())));
        Button close = Ui.button("Stop accepting codes", Icons.X, "btn-ghost");
        close.setOnAction(e -> {
            long id = live.sessionId();
            live = null;
            ctx.background().run(() -> {
                ctx.attendance().close(user, id);
                return null;
            }, r -> reload(), shell::showError);
        });
        VBox codeBox = new VBox(6, Ui.label("ATTENDANCE CODE", "caption"), codeLabel, expiryBar, expiry);
        codeBox.setAlignment(Pos.CENTER);
        codeBox.setMinWidth(320);
        VBox side = new VBox(10, Ui.label("Live", "caption"), presentCount,
                Ui.wrapping("The list below refreshes every few seconds. A new code appears automatically when this one expires.", "small", "muted"),
                Ui.row(8, renew, close), finalize);
        HBox body = new HBox(32, codeBox, side);
        body.setAlignment(Pos.CENTER_LEFT);
        startTimers();
        return Ui.card(body);
    }

    private void startTimers() {
        tick();
        ticker = new Timeline(new KeyFrame(Duration.seconds(1), e -> tick()));
        ticker.setCycleCount(Timeline.INDEFINITE);
        ticker.play();
        poller = new Timeline(new KeyFrame(Duration.seconds(4), e -> refreshRoster()));
        poller.setCycleCount(Timeline.INDEFINITE);
        poller.play();
    }

    private void stopTimers() {
        if (ticker != null) {
            ticker.stop();
        }
        if (poller != null) {
            poller.stop();
        }
    }

    private void tick() {
        if (live == null) {
            return;
        }
        long left = Math.max(0, java.time.Duration.between(Instant.now(), localExpiry).getSeconds());
        expiry.setText(left > 0 ? "Expires in " + left / 60 + ":" + "%02d".formatted(left % 60) : "Expired — showing a new code…");
        expiryBar.setProgress((double) left / ctx.attendance().codeSeconds());
        if (left == 0 && !renewing) {
            renewing = true;
            ctx.background().run(() -> ctx.attendance().newCode(user, live.sessionId()), code -> {
                renewing = false;
                setLive(code);
            }, e -> {
                renewing = false;
                shell.showError(e);
                reload();
            });
        }
    }

    private void refreshRoster() {
        ctx.background().run(() -> ctx.attendance().lecture(user, lecture.id()), a -> {
            if (a.openSession() == null || a.lecture().attendanceFinalized()) {
                live = null;
                showProfessor(a);
                return;
            }
            presentCount.setText(a.present() + " / " + a.records().size() + " present");
            // replace only the roster card, keep the live code card (and its timers) as they are
            if (root.getChildren().size() == 2) {
                root.getChildren().set(1, Ui.card("Students (" + a.records().size() + ")", roster(a, false)));
            }
        }, e -> { });
    }

    private interface CodeCall {
        LiveCode call() throws Exception;
    }

    private void act(CodeCall call) {
        ctx.background().run(call::call, this::setLive, shell::showError);
    }

    private void setLive(LiveCode code) {
        live = code;
        localExpiry = Instant.now().plusSeconds(code.secondsLeft());
        if (ticker != null && ticker.getStatus() == javafx.animation.Animation.Status.RUNNING) {
            codeLabel.setText(spaced(code.code()));
            tick();
        } else {
            reload();
        }
    }

    private static String spaced(String code) {
        return code.substring(0, 3) + " " + code.substring(3);
    }

    private void confirmFinalize(LectureAttendance a) {
        long missing = a.records().stream().filter(r -> r.status() == null).count();
        if (!Dialogs.confirm(shell.window(), "Finalize attendance",
                a.present() + " students are marked present. " + missing + " without a record will be marked ABSENT. "
                        + "After finalizing, codes are no longer accepted; you can still correct individual records with a reason.",
                "Finalize")) {
            return;
        }
        live = null;
        stopTimers();
        ctx.background().run(() -> ctx.attendance().finalizeAttendance(user, lecture.id()), counts -> {
            shell.toast().success("Attendance finalized: " + counts[0] + " present, " + counts[1] + " absent.");
            reload();
        }, e -> {
            shell.showError(e);
            reload();
        });
    }

    private Node roster(LectureAttendance a, boolean finalized) {
        TableView<AttendanceRecord> table = new TableView<>(FXCollections.observableArrayList(a.records()));
        table.setPlaceholder(Ui.label("No students enrolled.", "faint"));
        table.getColumns().add(CourseDetailPage.column("Student", AttendanceRecord::studentName, 220));
        table.getColumns().add(CourseDetailPage.column("Enrollment", AttendanceRecord::enrollmentNo, 130));
        TableColumn<AttendanceRecord, AttendanceRecord> status = new TableColumn<>("Status");
        status.setCellValueFactory(cd -> new javafx.beans.property.SimpleObjectProperty<>(cd.getValue()));
        status.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(AttendanceRecord r, boolean empty) {
                super.updateItem(r, empty);
                setGraphic(empty || r == null ? null : AttendanceWidgets.statusBadge(r.status()));
            }
        });
        status.setPrefWidth(120);
        table.getColumns().add(status);
        table.getColumns().add(CourseDetailPage.column("How", r -> r.source() == null ? "—" : switch (r.source()) {
            case "CODE" -> "Code";
            case "FINALIZE" -> "No code at finalization";
            default -> "Corrected";
        }, 180));
        table.getColumns().add(CourseDetailPage.column("Time", r -> r.markedAt() == null ? "—"
                : r.markedAt().atZone(ctx.lectures().zone()).format(Ui.TIME), 100));
        if (finalized) {
            TableColumn<AttendanceRecord, AttendanceRecord> action = new TableColumn<>("");
            action.setCellValueFactory(cd -> new javafx.beans.property.SimpleObjectProperty<>(cd.getValue()));
            action.setCellFactory(col -> new TableCell<>() {
                @Override
                protected void updateItem(AttendanceRecord r, boolean empty) {
                    super.updateItem(r, empty);
                    if (empty || r == null || r.status() == null) {
                        setGraphic(null);
                        return;
                    }
                    Button fix = Ui.button("Correct", Icons.NOTE, "btn-ghost");
                    fix.setOnAction(e -> correct(r));
                    setGraphic(fix);
                }
            });
            action.setPrefWidth(120);
            table.getColumns().add(action);
        }
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPrefHeight(Math.min(460, 44 + 42 * Math.max(2, a.records().size())));
        return table;
    }

    private void correct(AttendanceRecord r) {
        AttendanceStatus target = r.status() == AttendanceStatus.PRESENT ? AttendanceStatus.ABSENT : AttendanceStatus.PRESENT;
        ComboBox<AttendanceStatus> to = new ComboBox<>(FXCollections.observableArrayList(AttendanceStatus.values()));
        to.setValue(target);
        TextArea reason = Forms.area("", "e.g. Medical certificate submitted; was present but code screen not visible", 3);
        VBox form = new VBox(12,
                Ui.wrapping(r.studentName() + " (" + r.enrollmentNo() + ") is currently " + r.status().label().toLowerCase() + ".", "muted"),
                Ui.formRow("Change to", to, null),
                Ui.formRow("Reason", reason, "Required. Saved with your name, the time, and the old and new values."));
        boolean ok = Dialogs.form(shell.window(), "Correct attendance", form, "Save correction", () -> {
            if (to.getValue() == r.status()) {
                return "Choose a different status.";
            }
            return reason.getText().trim().length() < 5 ? "Give a reason of at least 5 characters." : null;
        });
        if (!ok) {
            return;
        }
        ctx.background().run(() -> {
            ctx.attendance().correct(user, lecture.id(), r.studentId(), to.getValue(), reason.getText());
            return null;
        }, x -> {
            shell.toast().success("Attendance corrected for " + r.studentName() + ".");
            reload();
        }, shell::showError);
    }

    private Node correctionsCard(LectureAttendance a) {
        VBox list = new VBox(8);
        if (a.corrections().isEmpty()) {
            list.getChildren().add(Ui.label("No corrections.", "faint"));
        }
        for (AttendanceCorrection c : a.corrections()) {
            VBox text = new VBox(2,
                    Ui.label(c.studentName() + ": " + c.oldStatus().label() + " → " + c.newStatus().label(), "body-strong"),
                    Ui.wrapping("“" + c.reason() + "”", "muted"),
                    Ui.label(c.correctedByName() + " · " + c.correctedAt().atZone(ctx.lectures().zone()).format(Ui.DATE_TIME), "small", "faint"));
            text.getStyleClass().add("list-row");
            list.getChildren().add(text);
        }
        return Ui.card("Correction history", list);
    }
}
