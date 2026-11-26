package com.classsync.view;

import com.classsync.AppContext;
import com.classsync.model.AttemptMode;
import com.classsync.model.Quiz;
import com.classsync.model.QuizAttempt;
import com.classsync.model.QuizStatus;
import com.classsync.model.User;
import com.classsync.service.QuizService.StudentQuiz;
import com.classsync.service.QuizService.StudentState;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.time.ZoneId;

/** Quiz rows and actions shared by the Quizzes page, the lecture Quizzes tab and the dashboard. */
final class QuizWidgets {

    private QuizWidgets() {
    }

    static Label stateBadge(StudentState s) {
        return Ui.badge(s.label(), switch (s) {
            case OPEN -> "primary";
            case IN_PROGRESS -> "warning";
            case AWAITING_RESULTS -> "info";
            case RESULT_RELEASED -> "success";
            case UPCOMING, MISSED -> "neutral";
        });
    }

    static Label statusBadge(Quiz q) {
        return q.status() == QuizStatus.DRAFT ? Ui.badge("Draft", "neutral") : Ui.badge("Published", "primary");
    }

    static String score(QuizAttempt a) {
        if (a == null || a.score() == null) {
            return "";
        }
        return a.score() + " / " + a.maxScore() + "  (" + Ui.percent(a.fraction()) + ")";
    }

    private static Node icon() {
        StackPane bg = new StackPane(Icons.of(Icons.QUIZ, 18));
        bg.getStyleClass().add("stat-icon-bg");
        bg.setMinSize(38, 38);
        bg.setMaxSize(38, 38);
        return bg;
    }

    private static String meta(Quiz q, ZoneId zone) {
        String lecture = q.lectureNo() == null ? "" : "  ·  L" + q.lectureNo();
        return q.courseCode() + lecture + "  ·  " + plural(q.questionCount(), "question") + ", "
                + plural(q.totalMarks(), "mark") + "  ·  " + q.durationMinutes() + " min";
    }

    static String plural(int n, String word) {
        return plural(n, word, word + "s");
    }

    static String plural(int n, String one, String many) {
        return n + " " + (n == 1 ? one : many);
    }

    /** A student's quiz row with the action that fits its state. */
    static HBox studentRow(AppContext ctx, AppShell shell, StudentQuiz sq) {
        Quiz q = sq.quiz();
        ZoneId zone = ctx.quizzes().zone();
        String when = switch (sq.state()) {
            case UPCOMING -> "Opens " + q.opensAt().atZone(zone).format(Ui.DATE_TIME);
            case MISSED -> "Closed " + q.closesAt().atZone(zone).format(Ui.DATE_TIME);
            // a submitted quiz may still be open for classmates
            default -> (q.closesAt().isAfter(ctx.clock().instant()) ? "Closes " : "Closed ")
                    + q.closesAt().atZone(zone).format(Ui.DATE_TIME);
        };
        VBox text = new VBox(3, Ui.label(q.title(), "body-strong"), Ui.label(meta(q, zone), "small", "muted"),
                Ui.label(when, "small", "faint"));
        HBox.setHgrow(text, Priority.ALWAYS);
        HBox actions = Ui.row(8, stateBadge(sq.state()));
        User u = ctx.session().requireUser();
        switch (sq.state()) {
            case OPEN -> {
                Button start = Ui.primary("Start", Icons.CHEVRON_RIGHT);
                start.setOnAction(e -> confirmStart(ctx, shell, u, q, AttemptMode.OFFICIAL));
                actions.getChildren().add(start);
            }
            case IN_PROGRESS -> {
                Button resume = Ui.primary("Resume", Icons.CHEVRON_RIGHT);
                resume.setOnAction(e -> shell.open(new QuizAttemptPage(ctx, shell, sq.openAttempt().id())));
                actions.getChildren().add(resume);
            }
            case RESULT_RELEASED -> {
                actions.getChildren().add(Ui.label(score(sq.official()), "body-strong"));
                Button view = Ui.button("Review", Icons.EYE);
                view.setOnAction(e -> shell.open(new QuizResultPage(ctx, shell, sq.official().id())));
                actions.getChildren().add(view);
            }
            default -> { }
        }
        if (sq.practiceAllowed()) {
            QuizAttempt open = sq.openAttempt();
            Button practice = Ui.button(open != null && open.mode() == AttemptMode.PRACTICE ? "Resume practice" : "Practice",
                    Icons.REVISION, "btn-ghost");
            practice.setOnAction(e -> {
                if (open != null && open.mode() == AttemptMode.PRACTICE) {
                    shell.open(new QuizAttemptPage(ctx, shell, open.id()));
                } else {
                    confirmStart(ctx, shell, u, q, AttemptMode.PRACTICE);
                }
            });
            actions.getChildren().add(practice);
        }
        HBox row = new HBox(14, icon(), text, actions);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("list-row");
        return row;
    }

    /** A professor's quiz row; opens the editor. */
    static HBox professorRow(AppContext ctx, AppShell shell, Quiz q) {
        ZoneId zone = ctx.quizzes().zone();
        VBox text = new VBox(3, Ui.label(q.title(), "body-strong"), Ui.label(meta(q, zone), "small", "muted"),
                Ui.label(QuizForms.window(q, zone), "small", "faint"));
        HBox.setHgrow(text, Priority.ALWAYS);
        HBox row = new HBox(14, icon(), text, Ui.label(q.submissions() + " submitted", "small", "muted"), statusBadge(q));
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().addAll("list-row", "list-row-clickable");
        Ui.clickable(row, () -> shell.open(new QuizEditorPage(ctx, shell, q.id())));
        row.getStyleClass().remove("card-clickable");
        return row;
    }

    /** Explains the rules, then starts (or resumes) the attempt and opens the attempt screen. */
    static void confirmStart(AppContext ctx, AppShell shell, User u, Quiz q, AttemptMode mode) {
        String rules = (q.instructions().isBlank() ? "" : q.instructions().strip() + "\n\n")
                + "• " + plural(q.questionCount(), "question") + ", " + plural(q.totalMarks(), "mark") + ".\n"
                + "• Time limit: " + q.durationMinutes() + " minutes (never past the closing time). The timer keeps running "
                + "if you leave this screen or close ClassSync.\n"
                + "• Every answer is saved as soon as you choose it. When time runs out, your saved answers are submitted automatically.\n"
                + (mode == AttemptMode.OFFICIAL ? "• You have one official attempt." : "• Practice attempts do not change your official mark.");
        if (!Dialogs.confirm(shell.window(), (mode == AttemptMode.PRACTICE ? "Practice: " : "") + q.title(), rules,
                mode == AttemptMode.PRACTICE ? "Start practice" : "Start quiz")) {
            return;
        }
        ctx.background().run(() -> ctx.quizzes().start(u, q.id(), mode),
                id -> shell.open(new QuizAttemptPage(ctx, shell, id)), shell::showError);
    }
}
