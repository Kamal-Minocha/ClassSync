package com.classsync.view;

import com.classsync.AppContext;
import com.classsync.model.Course;
import com.classsync.model.Quiz;
import com.classsync.model.Role;
import com.classsync.model.User;
import com.classsync.service.QuizAnalyticsService.TopicInsight;
import com.classsync.service.QuizRules;
import com.classsync.service.QuizService.StudentQuiz;
import com.classsync.service.QuizService.StudentState;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Students/CRs: their quizzes grouped by state, plus their weak topics.
 * Professors: every quiz in their courses with a "New quiz" action.
 */
public class QuizzesPage extends Page {

    public QuizzesPage(AppContext ctx, AppShell shell) {
        super(ctx, shell);
    }

    @Override
    public Nav nav() {
        return Nav.QUIZZES;
    }

    @Override
    public Node build() {
        User u = user();
        return u.role() == Role.PROFESSOR ? professor(u) : student(u);
    }

    // ---------------------------------------------------------------- professor

    private Node professor(User u) {
        VBox list = new VBox(10, Ui.loading("Loading quizzes…"));
        ComboBox<Object> filter = new ComboBox<>();
        filter.getItems().add("All courses");
        filter.setValue("All courses");
        filter.setConverter(QuizForms.converter(o -> o instanceof Course c ? c.display() : o.toString()));
        Button create = Ui.primary("New quiz", Icons.PLUS);
        create.setDisable(true);
        java.util.concurrent.atomic.AtomicReference<List<Course>> courses = new java.util.concurrent.atomic.AtomicReference<>(List.of());

        Runnable reload = () -> {
            Long courseId = filter.getValue() instanceof Course c ? c.id() : null;
            load(() -> ctx.quizzes().list(u, courseId, null), quizzes -> {
                list.getChildren().clear();
                if (quizzes.isEmpty()) {
                    list.getChildren().add(Ui.emptyState(Icons.QUIZ, "No quizzes yet",
                            "Create a quiz for a lecture: add questions, publish it, and watch results arrive.", null));
                }
                for (Quiz q : quizzes) {
                    list.getChildren().add(QuizWidgets.professorRow(ctx, shell, q));
                }
            });
        };
        filter.setOnAction(e -> reload.run());
        create.setOnAction(e -> {
            Long preselect = filter.getValue() instanceof Course c ? c.id() : null;
            QuizForms.quiz(ctx, shell, courses.get(), null, preselect, null, id -> shell.open(new QuizEditorPage(ctx, shell, id)));
        });
        load(() -> ctx.courses().myCourses(u, false), mine -> {
            courses.set(mine);
            filter.getItems().addAll(mine);
            create.setDisable(mine.isEmpty());
        });
        reload.run();
        return Ui.page(new VBox(16,
                Ui.pageHeader("Quizzes", "Lecture quizzes in your courses. Open one to edit questions or see results.", create),
                Ui.row(8, Ui.label("Course", "field-label"), filter), list));
    }

    // ---------------------------------------------------------------- student / CR

    private Node student(User u) {
        VBox body = new VBox(20, Ui.pageHeader("Quizzes",
                "Timed lecture quizzes. Answers save as you go; results appear when your professor allows."),
                Ui.loading("Loading quizzes…"));
        record Data(List<StudentQuiz> quizzes, List<TopicInsight> topics) { }
        load(() -> new Data(ctx.quizzes().forStudent(u, null, null), ctx.quizAnalytics().weakTopicsForStudent(u)), d -> {
            body.getChildren().remove(1);
            Set<StudentState> now = EnumSet.of(StudentState.OPEN, StudentState.IN_PROGRESS);
            body.getChildren().addAll(
                    section("Open now", d.quizzes().stream().filter(s -> now.contains(s.state()))
                                    .sorted(java.util.Comparator.comparing(s -> s.quiz().closesAt())).toList(),
                            "No quiz is open right now."),
                    new HBox(20, grow(section("Upcoming", d.quizzes().stream().filter(s -> s.state() == StudentState.UPCOMING)
                                    .sorted(java.util.Comparator.comparing(s -> s.quiz().opensAt())).toList(),
                                    "Nothing scheduled.")),
                            weakTopicsCard(d.topics())),
                    section("Completed and closed", d.quizzes().stream().filter(s -> !now.contains(s.state())
                            && s.state() != StudentState.UPCOMING).toList(), "No finished quizzes yet."));
        });
        return Ui.page(body);
    }

    private static Node grow(Node n) {
        HBox.setHgrow(n, Priority.ALWAYS);
        return n;
    }

    private Node section(String title, List<StudentQuiz> items, String empty) {
        VBox list = new VBox(10);
        if (items.isEmpty()) {
            list.getChildren().add(Ui.label(empty, "faint"));
        }
        for (StudentQuiz sq : items) {
            list.getChildren().add(QuizWidgets.studentRow(ctx, shell, sq));
        }
        return Ui.card(title, list);
    }

    /** Weak Topic Detector card, shared with the student dashboard. */
    static VBox weakTopicsCard(List<TopicInsight> topics) {
        VBox list = new VBox(8);
        List<TopicInsight> weak = topics.stream().filter(t -> t.verdict() == QuizRules.Verdict.WEAK).toList();
        List<TopicInsight> judged = topics.stream().filter(t -> t.verdict() != QuizRules.Verdict.INSUFFICIENT_DATA).toList();
        if (topics.isEmpty() || judged.isEmpty()) {
            list.getChildren().add(Ui.wrapping("Not enough quiz data yet. A topic is assessed once you have answered at least "
                    + QuizRules.MIN_STUDENT_SAMPLE + " released questions on it.", "muted"));
        } else if (weak.isEmpty()) {
            list.getChildren().add(Ui.wrapping("No weak topics — you are scoring at least "
                    + Ui.percent(QuizRules.WEAK_THRESHOLD) + " on every assessed topic.", "muted"));
        }
        for (TopicInsight t : topics.stream().limit(5).toList()) {
            list.getChildren().add(QuizEditorPage.topicRow(t));
        }
        VBox card = Ui.card("Weak topics", list);
        card.setPrefWidth(420);
        card.setMinWidth(340);
        return card;
    }
}
