package com.classsync.view;

import com.classsync.AppContext;
import com.classsync.dao.AttemptDao.SubmissionRow;
import com.classsync.model.Question;
import com.classsync.model.QuestionOption;
import com.classsync.model.Quiz;
import com.classsync.model.QuizStatus;
import com.classsync.model.ReviewPolicy;
import com.classsync.model.User;
import com.classsync.service.Errors;
import com.classsync.service.QuizAnalyticsService.QuestionStat;
import com.classsync.service.QuizAnalyticsService.QuizAnalytics;
import com.classsync.service.QuizAnalyticsService.TopicInsight;
import com.classsync.service.QuizRules;
import com.classsync.service.QuizService.Editor;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Button;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.List;

/** Professor view of one quiz: settings, questions with answer keys, publication and results analytics. */
public class QuizEditorPage extends Page {

    private final long quizId;
    private Editor editor;

    public QuizEditorPage(AppContext ctx, AppShell shell, long quizId) {
        super(ctx, shell);
        this.quizId = quizId;
    }

    @Override
    public Nav nav() {
        return Nav.QUIZZES;
    }

    @Override
    public Node build() {
        User u = user();
        StackPane root = new StackPane(Ui.loading("Loading quiz…"));
        load(() -> ctx.quizzes().editor(u, quizId), e -> {
            editor = e;
            root.getChildren().setAll(Ui.pageFixed(header(u), tabs(u)));
        }, e -> root.getChildren().setAll(Ui.errorState(Errors.userMessage(e), null)));
        return root;
    }

    // ---------------------------------------------------------------- header

    private Node header(User u) {
        Quiz q = editor.quiz();
        var zone = ctx.quizzes().zone();
        Button back = Ui.button("Quizzes", Icons.CHEVRON_LEFT, "btn-ghost");
        back.setOnAction(e -> shell.open(Nav.QUIZZES));

        HBox tags = Ui.row(6, Ui.badge(q.courseCode(), "primary"),
                q.lectureNo() == null ? Ui.label("") : Ui.badge("Lecture " + q.lectureNo(), "neutral"), QuizWidgets.statusBadge(q));
        if (editor.locked()) {
            tags.getChildren().add(Ui.badge("Frozen — attempts started", "warning"));
        }
        VBox text = new VBox(8, tags, Ui.wrapping(q.title(), "h1"),
                Ui.row(16, Ui.row(6, Icons.of(Icons.CALENDAR, 16), Ui.label(QuizForms.window(q, zone), "muted")),
                        Ui.row(6, Icons.of(Icons.CLOCK, 16), Ui.label(q.durationMinutes() + " min", "muted")),
                        Ui.label(QuizWidgets.plural(q.questionCount(), "question") + " · " + QuizWidgets.plural(q.totalMarks(), "mark"), "muted")),
                Ui.label("Answers shown: " + q.reviewPolicy().label().toLowerCase()
                        + (q.allowPractice() ? " · practice re-attempts allowed" : " · no practice re-attempts"), "small", "faint"));
        HBox.setHgrow(text, Priority.ALWAYS);

        HBox actions = new HBox(8);
        actions.setAlignment(Pos.TOP_RIGHT);
        if (!editor.locked()) {
            Button settings = Ui.button("Edit settings", Icons.SETTINGS);
            settings.setOnAction(e -> ctx.background().run(() -> ctx.courses().get(u, q.courseId()),
                    c -> QuizForms.quiz(ctx, shell, List.of(c), q, q.courseId(), q.lectureId(), id -> shell.refresh()), shell::showError));
            actions.getChildren().add(settings);
        }
        if (q.status() == QuizStatus.DRAFT) {
            Button publish = Ui.primary("Publish", Icons.CHECK);
            publish.setOnAction(e -> act(() -> ctx.quizzes().publish(u, quizId), "Quiz published. Students can see it now."));
            actions.getChildren().add(publish);
            Button delete = Ui.button("Delete", Icons.X, "btn-ghost");
            delete.setOnAction(e -> {
                if (Dialogs.confirm(shell.window(), "Delete quiz", "Delete this draft quiz and its questions?", "Delete")) {
                    ctx.background().run(() -> {
                        ctx.quizzes().deleteDraft(u, quizId);
                        return null;
                    }, r -> shell.open(Nav.QUIZZES), shell::showError);
                }
            });
            actions.getChildren().add(delete);
        } else {
            if (!editor.locked()) {
                Button unpublish = Ui.button("Unpublish", null, "btn-ghost");
                unpublish.setOnAction(e -> act(() -> ctx.quizzes().unpublish(u, quizId), "Quiz moved back to draft."));
                actions.getChildren().add(unpublish);
            }
            if (q.reviewPolicy() == ReviewPolicy.MANUAL && q.resultsReleasedAt() == null) {
                Button release = Ui.primary("Release results", Icons.EYE);
                release.setOnAction(e -> {
                    if (Dialogs.confirm(shell.window(), "Release results",
                            "Students will see their scores, the correct answers and explanations. This cannot be undone.", "Release")) {
                        act(() -> ctx.quizzes().releaseResults(u, quizId), "Results released to students.");
                    }
                });
                actions.getChildren().add(release);
            } else if (q.reviewPolicy() == ReviewPolicy.MANUAL) {
                actions.getChildren().add(Ui.badge("Results released", "success"));
            }
        }
        return new VBox(8, back, new HBox(16, text, actions));
    }

    private interface Action {
        void run() throws Exception;
    }

    private void act(Action action, String message) {
        ctx.background().run(() -> {
            action.run();
            return null;
        }, r -> {
            shell.toast().success(message);
            shell.refresh();
        }, shell::showError);
    }

    private Node tabs(User u) {
        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getTabs().add(new Tab("Questions (" + editor.questions().size() + ")", Ui.tabScroll(questions(u))));
        Tab results = new Tab("Results", Ui.tabScroll(results(u)));
        results.setDisable(editor.quiz().status() == QuizStatus.DRAFT);
        tabs.getTabs().add(results);
        if (editor.quiz().submissions() > 0) {
            tabs.getSelectionModel().select(results);
        }
        return tabs;
    }

    // ---------------------------------------------------------------- questions

    private Node questions(User u) {
        VBox list = new VBox(14);
        Quiz q = editor.quiz();
        if (!editor.locked()) {
            Button add = Ui.primary("Add question", Icons.PLUS);
            add.setOnAction(e -> openQuestion(u, null));
            list.getChildren().add(Ui.row(8, add, Ui.wrapping("Tip: link questions to syllabus topics so the weak-topic detector can use them.", "small", "faint")));
        } else {
            list.getChildren().add(Ui.wrapping("Students have started this quiz, so questions can no longer change. "
                    + "This keeps every student's attempt and score consistent.", "muted"));
        }
        if (editor.questions().isEmpty()) {
            list.getChildren().add(Ui.emptyState(Icons.QUIZ, "No questions yet", "Add multiple-choice or true/false questions, then publish.", null));
        }
        for (Question question : editor.questions()) {
            list.getChildren().add(questionCard(u, q, question));
        }
        return list;
    }

    private void openQuestion(User u, Question existing) {
        ctx.background().run(() -> ctx.courses().topics(u, editor.quiz().courseId()),
                topics -> QuizForms.question(ctx, shell, editor.quiz(), existing, topics, shell::refresh), shell::showError);
    }

    private Node questionCard(User u, Quiz q, Question question) {
        VBox options = new VBox(6);
        char letter = 'A';
        for (QuestionOption o : question.options()) {
            HBox row = Ui.row(10, o.correct() ? Icons.of(Icons.CHECK, 16) : Ui.label("  "),
                    Ui.label(letter + ".", "body-strong"), Ui.wrapping(o.text()));
            row.getStyleClass().add("option-row");
            if (o.correct()) {
                row.getStyleClass().add("option-correct");
            }
            options.getChildren().add(row);
            letter++;
        }
        HBox top = Ui.row(8, Ui.label("Q" + question.position(), "caption"), Ui.badge(question.kind().label(), "neutral"),
                Ui.badge(question.marks() + (question.marks() == 1 ? " mark" : " marks"), "neutral"),
                question.topicName() == null ? Ui.label("") : Ui.badge(question.topicName(), "info"), Ui.spacer());
        if (!editor.locked()) {
            Button edit = Ui.iconButton(Icons.NOTE, "Edit question " + question.position());
            edit.setOnAction(e -> openQuestion(u, question));
            Button delete = Ui.iconButton(Icons.X, "Delete question " + question.position());
            delete.setOnAction(e -> {
                if (Dialogs.confirm(shell.window(), "Delete question", "Delete question " + question.position() + "?", "Delete")) {
                    act(() -> ctx.quizzes().deleteQuestion(u, question.id()), "Question deleted.");
                }
            });
            top.getChildren().addAll(edit, delete);
        }
        VBox card = Ui.card(top, Ui.wrapping(question.prompt(), "h3"), options);
        if (!question.explanation().isBlank()) {
            card.getChildren().add(Ui.wrapping("Explanation: " + question.explanation(), "small", "muted"));
        }
        return card;
    }

    // ---------------------------------------------------------------- results

    private Node results(User u) {
        VBox box = new VBox(20, Ui.loading("Calculating results…"));
        if (editor.quiz().status() == QuizStatus.DRAFT) {
            return box;
        }
        record Data(QuizAnalytics a, List<TopicInsight> topics) { }
        load(() -> new Data(ctx.quizAnalytics().analytics(u, quizId),
                        ctx.quizAnalytics().weakTopicsForCourse(u, editor.quiz().courseId())),
                d -> box.getChildren().setAll(resultsContent(d.a(), d.topics())),
                e -> box.getChildren().setAll(Ui.errorState(Errors.userMessage(e), null)));
        return box;
    }

    private List<Node> resultsContent(QuizAnalytics a, List<TopicInsight> topics) {
        int max = a.quiz().totalMarks();
        String participation = a.participation().isPresent()
                ? a.participants() + " / " + a.enrolled() : "—";
        String avg = a.averageScore() == null ? "—" : "%.1f / %d".formatted(a.averageScore(), max);
        HBox stats = new HBox(16,
                Ui.statCard(Icons.USERS, participation, "Participation",
                        a.participation().isPresent() ? Ui.percent(a.participation().getAsDouble()) + " of enrolled students"
                                + (a.inProgress() > 0 ? " · " + a.inProgress() + " in progress" : "") : "No students enrolled", null),
                Ui.statCard(Icons.CHART, avg, "Average score",
                        a.averageScore() == null ? "No submissions yet" : Ui.percent(a.averageScore() / max), "stat-teal"),
                Ui.statCard(Icons.TROPHY, a.highest() == null ? "—" : a.highest() + " / " + max, "Highest", null, "stat-green"),
                Ui.statCard(Icons.TARGET, a.lowest() == null ? "—" : a.lowest() + " / " + max, "Lowest", null, "stat-amber"));

        Node chart = a.finished() == 0
                ? Ui.emptyState(Icons.CHART, "No submissions yet", "Question accuracy appears after the first submission.", null)
                : accuracyChart(a.questions());
        VBox chartCard = Ui.card("Question accuracy (official attempts)", chart);

        TableView<QuestionStat> questionTable = new TableView<>();
        questionTable.getColumns().add(CourseDetailPage.column("Q", s -> "Q" + s.position(), 50));
        questionTable.getColumns().add(CourseDetailPage.column("Question", QuestionStat::prompt, 300));
        questionTable.getColumns().add(CourseDetailPage.column("Topic", s -> s.topic() == null ? "—" : s.topic(), 140));
        questionTable.getColumns().add(CourseDetailPage.column("Accuracy",
                s -> s.accuracy().isPresent() ? Ui.percent(s.accuracy().getAsDouble()) + "  (" + s.correct() + "/" + s.finished() + ")" : "—", 120));
        questionTable.getColumns().add(CourseDetailPage.column("Most chosen wrong answer",
                s -> s.commonWrong() == null ? "—" : s.commonWrong() + "  (" + s.commonWrongCount() + ")", 220));
        questionTable.getItems().setAll(a.questions());
        questionTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        questionTable.setPrefHeight(Math.min(420, 44 + 40 * Math.max(1, a.questions().size())));

        TableView<SubmissionRow> subs = new TableView<>();
        subs.setPlaceholder(Ui.label("No attempts yet.", "faint"));
        subs.getColumns().add(CourseDetailPage.column("Student", s -> s.studentName() + (s.enrolled() ? "" : " (unenrolled)"), 200));
        subs.getColumns().add(CourseDetailPage.column("Enrollment", s -> s.enrollmentNo() == null ? "" : s.enrollmentNo(), 120));
        subs.getColumns().add(CourseDetailPage.column("Status", s -> s.status().label(), 170));
        subs.getColumns().add(CourseDetailPage.column("Score", s -> s.score() == null ? "—" : s.score() + " / " + s.maxScore(), 90));
        subs.getColumns().add(CourseDetailPage.column("Submitted", s -> s.submittedAt() == null ? "—"
                : s.submittedAt().atZone(ctx.quizzes().zone()).format(Ui.DATE_TIME), 170));
        subs.getItems().setAll(a.submissions());
        subs.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        subs.setPrefHeight(Math.min(420, 44 + 40 * Math.max(2, a.submissions().size())));

        return List.of(stats, chartCard, Ui.card("Questions", questionTable), Ui.card("Submissions", subs), topicsCard(topics));
    }

    private Node accuracyChart(List<QuestionStat> stats) {
        CategoryAxis x = new CategoryAxis();
        NumberAxis y = new NumberAxis(0, 100, 20);
        y.setLabel("% correct");
        BarChart<String, Number> chart = new BarChart<>(x, y);
        chart.setLegendVisible(false);
        chart.setAnimated(false);
        chart.setPrefHeight(260);
        XYChart.Series<String, Number> series = new XYChart.Series<>();
        for (QuestionStat s : stats) {
            series.getData().add(new XYChart.Data<>("Q" + s.position(), Math.round(s.accuracy().orElse(0) * 100)));
        }
        chart.getData().add(series);
        return chart;
    }

    private Node topicsCard(List<TopicInsight> topics) {
        VBox list = new VBox(8, Ui.wrapping("Across all published quizzes in this course. A topic is flagged when at least "
                + QuizRules.MIN_CLASS_SAMPLE + " answers exist and fewer than " + Ui.percent(QuizRules.WEAK_THRESHOLD)
                + " are correct.", "small", "faint"));
        if (topics.isEmpty()) {
            list.getChildren().add(Ui.label("No answers on syllabus-linked questions yet.", "muted"));
        }
        for (TopicInsight t : topics) {
            list.getChildren().add(topicRow(t));
        }
        return Ui.card("Class weak topics", list);
    }

    static HBox topicRow(TopicInsight t) {
        String tone = switch (t.verdict()) {
            case WEAK -> "danger";
            case OK -> "success";
            case INSUFFICIENT_DATA -> "neutral";
        };
        String detail = t.verdict() == QuizRules.Verdict.INSUFFICIENT_DATA
                ? t.seen() + " answered so far"
                : Ui.percent(t.accuracy().orElse(0)) + " correct (" + t.correct() + "/" + t.seen() + ")";
        VBox text = new VBox(2, Ui.wrapping(t.topic(), "body-strong"), Ui.label(t.courseCode() + "  ·  " + detail, "small", "muted"));
        HBox.setHgrow(text, Priority.ALWAYS);
        text.setMinWidth(0);
        HBox row = Ui.row(10, text, Ui.badge(t.verdict().label(), tone));
        row.getStyleClass().add("list-row");
        return row;
    }
}
