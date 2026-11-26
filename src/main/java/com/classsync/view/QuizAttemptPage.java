package com.classsync.view;

import com.classsync.AppContext;
import com.classsync.model.AttemptMode;
import com.classsync.model.StudentQuestion;
import com.classsync.model.User;
import com.classsync.service.AppException;
import com.classsync.service.Errors;
import com.classsync.service.QuizService.AttemptSession;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Timed quiz attempt: question navigator, one question at a time, autosave, countdown and submit.
 *
 * The countdown is only a display. The real deadline lives in PostgreSQL: saving or submitting after it
 * is refused by the service, and an expired attempt is auto-submitted the next time it is touched,
 * even if ClassSync was closed or crashed in between.
 */
public class QuizAttemptPage extends Page {

    private final long attemptId;
    private AttemptSession session;
    private List<StudentQuestion> questions = List.of();
    private final Map<Long, Long> answers = new HashMap<>();      // questionId -> optionId (what the student sees)
    private final Map<Long, Long> unsaved = new LinkedHashMap<>(); // answers not yet confirmed by the database
    private boolean saving;
    private boolean submitRequested;
    private boolean finished;
    private int current;
    private Instant localDeadline;
    private Timeline timeline;

    private final Label timer = Ui.label("--:--", "timer");
    private final Label saveStatus = Ui.label("Answers save automatically", "small", "muted");
    private final FlowPane navigator = new FlowPane(6, 6);
    private final VBox questionBox = new VBox(14);
    private final Button prev = Ui.button("Previous", Icons.CHEVRON_LEFT);
    private final Button next = Ui.button("Next", Icons.CHEVRON_RIGHT);
    private final Button submit = Ui.primary("Submit", Icons.CHECK);

    public QuizAttemptPage(AppContext ctx, AppShell shell, long attemptId) {
        super(ctx, shell);
        this.attemptId = attemptId;
    }

    @Override
    public Nav nav() {
        return Nav.QUIZZES;
    }

    @Override
    public Node build() {
        User u = user();
        StackPane root = new StackPane(Ui.loading("Loading your attempt…"));
        // stop the countdown when the user navigates away (the attempt itself keeps its database deadline)
        root.sceneProperty().addListener((o, a, scene) -> {
            if (scene == null && timeline != null) {
                timeline.stop();
            }
        });
        load(() -> ctx.quizzes().session(u, attemptId), s -> {
            if (s.attempt().status().finished()) {
                shell.open(new QuizResultPage(ctx, shell, attemptId));
                return;
            }
            session = s;
            questions = s.questions();
            answers.putAll(s.saved());
            localDeadline = Instant.now().plusSeconds(s.secondsLeft());
            root.getChildren().setAll(layout());
            showQuestion(0);
            startTimer();
        }, e -> root.getChildren().setAll(Ui.errorState(Errors.userMessage(e), null)));
        return root;
    }

    // ---------------------------------------------------------------- layout

    private Node layout() {
        boolean practice = session.attempt().mode() == AttemptMode.PRACTICE;
        VBox titleBox = new VBox(4,
                Ui.row(6, Ui.badge(session.quiz().courseCode(), "primary"),
                        practice ? Ui.badge("Practice — not counted", "warning") : Ui.badge("Official attempt", "neutral")),
                Ui.label(session.quiz().title(), "h2"));
        HBox.setHgrow(titleBox, Priority.ALWAYS);
        VBox timerBox = new VBox(2, Ui.label("TIME LEFT", "caption"), timer);
        timerBox.setAlignment(Pos.CENTER_RIGHT);
        submit.setOnAction(e -> confirmSubmit());
        HBox top = new HBox(18, titleBox, saveStatus, timerBox, submit);
        top.setAlignment(Pos.CENTER_LEFT);
        top.getStyleClass().add("card");

        VBox nav = Ui.card("Questions", navigator,
                new VBox(6, legend("qnav-answered", "Answered"), legend(null, "Not answered yet")));
        nav.setPrefWidth(240);
        nav.setMinWidth(220);

        prev.setOnAction(e -> showQuestion(current - 1));
        next.setOnAction(e -> showQuestion(current + 1));
        VBox main = new VBox(18, questionBox, Ui.row(8, prev, Ui.spacer(), next));
        main.getStyleClass().add("card");
        HBox.setHgrow(main, Priority.ALWAYS);

        HBox body = new HBox(18, nav, main);
        body.setAlignment(Pos.TOP_LEFT);
        return Ui.page(new VBox(18, top, body));
    }

    /** Small colour key under the navigator: a sample square styled like a navigator button. */
    private static Node legend(String style, String text) {
        javafx.scene.layout.Region square = new javafx.scene.layout.Region();
        square.getStyleClass().addAll("button", "qnav-button");
        if (style != null) {
            square.getStyleClass().add(style);
        }
        square.setMinSize(16, 16);
        square.setPrefSize(16, 16);
        square.setMaxSize(16, 16);
        square.setStyle("-fx-min-width: 16; -fx-min-height: 16;");
        Label l = Ui.label(text, "small", "muted");
        l.setMinWidth(Label.USE_PREF_SIZE);
        return Ui.row(6, square, l);
    }

    private void refreshNavigator() {
        navigator.getChildren().clear();
        for (int i = 0; i < questions.size(); i++) {
            int index = i;
            Button b = new Button(String.valueOf(i + 1));
            b.getStyleClass().add("qnav-button");
            if (i == current) {
                b.getStyleClass().add("qnav-current");
            } else if (answers.containsKey(questions.get(i).id())) {
                b.getStyleClass().add("qnav-answered");
            }
            b.setAccessibleText("Question " + (i + 1) + (answers.containsKey(questions.get(i).id()) ? ", answered" : ", not answered"));
            b.setOnAction(e -> showQuestion(index));
            navigator.getChildren().add(b);
        }
    }

    private void showQuestion(int index) {
        if (index < 0 || index >= questions.size()) {
            return;
        }
        current = index;
        StudentQuestion q = questions.get(index);
        ToggleGroup group = new ToggleGroup();
        VBox options = new VBox(10);
        char letter = 'A';
        for (StudentQuestion.Choice c : q.choices()) {
            RadioButton radio = new RadioButton();
            radio.setToggleGroup(group);
            radio.setUserData(c.id());
            radio.setAccessibleText("Option " + letter + ": " + c.text());
            radio.setDisable(finished);
            Label text = Ui.wrapping(c.text());
            HBox.setHgrow(text, Priority.ALWAYS);
            HBox row = new HBox(12, radio, Ui.label(letter + ".", "body-strong"), text);
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("option-row");
            if (Long.valueOf(c.id()).equals(answers.get(q.id()))) {
                radio.setSelected(true);
                row.getStyleClass().add("option-selected");
            }
            row.setOnMouseClicked(e -> {
                if (!finished) {
                    radio.setSelected(true);
                }
            });
            options.getChildren().add(row);
            letter++;
        }
        group.selectedToggleProperty().addListener((o, a, t) -> {
            if (t != null && !finished) {
                choose(q.id(), (Long) t.getUserData());
                options.getChildren().forEach(n -> n.getStyleClass().remove("option-selected"));
                ((Node) t).getParent().getStyleClass().add("option-selected");
            }
        });
        questionBox.getChildren().setAll(
                Ui.row(8, Ui.label("QUESTION " + (index + 1) + " OF " + questions.size(), "caption"), Ui.spacer(),
                        Ui.badge(q.marks() + (q.marks() == 1 ? " mark" : " marks"), "neutral")),
                Ui.wrapping(q.prompt(), "h3"),
                options);
        prev.setDisable(index == 0);
        next.setDisable(index == questions.size() - 1);
        refreshNavigator();
    }

    // ---------------------------------------------------------------- autosave

    private void choose(long questionId, long optionId) {
        answers.put(questionId, optionId);
        unsaved.put(questionId, optionId);
        refreshNavigator();
        flush();
    }

    /**
     * Sends unsaved answers to the database one batch at a time. Batches never overlap, so an older
     * choice can never overwrite a newer one.
     */
    private void flush() {
        if (saving || finished) {
            return;
        }
        if (unsaved.isEmpty()) {
            if (submitRequested) {
                doSubmit();
            }
            return;
        }
        Map<Long, Long> batch = new LinkedHashMap<>(unsaved);
        unsaved.clear();
        saving = true;
        saveStatus.setText("Saving…");
        User u = user();
        ctx.background().run(() -> {
            for (var e : batch.entrySet()) {
                ctx.quizzes().saveAnswer(u, attemptId, e.getKey(), e.getValue());
            }
            return null;
        }, r -> {
            saving = false;
            saveStatus.setText("All answers saved");
            flush();
        }, e -> {
            saving = false;
            if (e instanceof AppException.ValidationException) {
                // deadline passed or attempt already submitted: the server has finalised it
                finished = true;
                stopTimer();
                shell.toast().info(e.getMessage());
                shell.open(new QuizResultPage(ctx, shell, attemptId));
            } else {
                // e.g. connection lost: keep the answers and retry on the next change or on submit
                batch.forEach(unsaved::putIfAbsent);
                saveStatus.setText("Not saved — check the connection");
                shell.showError(e);
            }
        });
    }

    // ---------------------------------------------------------------- timer and submit

    private void startTimer() {
        updateTimer();
        timeline = new Timeline(new KeyFrame(Duration.seconds(1), e -> updateTimer()));
        timeline.setCycleCount(Timeline.INDEFINITE);
        timeline.play();
    }

    private void stopTimer() {
        if (timeline != null) {
            timeline.stop();
        }
    }

    private void updateTimer() {
        long left = Math.max(0, java.time.Duration.between(Instant.now(), localDeadline).getSeconds());
        timer.setText("%02d:%02d".formatted(left / 60, left % 60));
        if (left <= 60 && !timer.getStyleClass().contains("timer-warning")) {
            timer.getStyleClass().add("timer-warning");
        }
        if (left == 0 && !submitRequested && !finished) {
            shell.toast().info("Time is up — submitting your saved answers.");
            requestSubmit();
        }
    }

    private void confirmSubmit() {
        long answered = questions.stream().filter(q -> answers.containsKey(q.id())).count();
        String message = "You answered " + answered + " of " + questions.size() + " questions."
                + (answered < questions.size() ? " Unanswered questions score zero." : "")
                + " You cannot change answers after submitting.";
        if (Dialogs.confirm(shell.window(), "Submit quiz?", message, "Submit")) {
            requestSubmit();
        }
    }

    /** Submits once all pending saves have reached the database. */
    private void requestSubmit() {
        submitRequested = true;
        submit.setDisable(true);
        saveStatus.setText("Submitting…");
        flush();
    }

    private void doSubmit() {
        if (finished) {
            return;
        }
        finished = true;
        stopTimer();
        User u = user();
        // submit is idempotent on the server, so a retry after a lost connection is safe
        ctx.background().run(() -> ctx.quizzes().submit(u, attemptId),
                r -> shell.open(new QuizResultPage(ctx, shell, attemptId)),
                e -> {
                    finished = false;
                    submitRequested = false;
                    submit.setDisable(false);
                    saveStatus.setText("Not submitted — try again");
                    shell.showError(e);
                });
    }
}
