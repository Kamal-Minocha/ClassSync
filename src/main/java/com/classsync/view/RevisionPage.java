package com.classsync.view;

import com.classsync.AppContext;
import com.classsync.dao.ProgressDao;
import com.classsync.model.Lecture;
import com.classsync.model.LectureStatus;
import com.classsync.model.User;
import com.classsync.service.QuizService.StudentQuiz;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Revision mode: completed lectures with their key concepts, questions answered wrongly (released only),
 * practice re-attempts (never official) and the student's revision history.
 */
public class RevisionPage extends Page {

    public RevisionPage(AppContext ctx, AppShell shell) {
        super(ctx, shell);
    }

    @Override
    public Nav nav() {
        return Nav.REVISION;
    }

    @Override
    public Node build() {
        User u = user();
        StackPane root = new StackPane(Ui.loading("Preparing revision…"));
        record Data(List<Lecture> completed, List<ProgressDao.MistakeRow> mistakes, List<StudentQuiz> practice,
                    List<ProgressDao.RevisionRow> history) { }
        load(() -> new Data(
                ctx.lectures().previous(u, 300).stream().filter(l -> l.status() == LectureStatus.COMPLETED).toList(),
                ctx.progress().mistakes(u),
                ctx.quizzes().forStudent(u, null, null).stream().filter(StudentQuiz::practiceAllowed).toList(),
                ctx.progress().revisions(u, 100)), d -> {
            TabPane tabs = new TabPane();
            tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
            tabs.getTabs().add(new Tab("Completed lectures (" + d.completed().size() + ")", Ui.tabScroll(lectures(u, d.completed(), d.history()))));
            tabs.getTabs().add(new Tab("Questions to revise (" + d.mistakes().size() + ")", Ui.tabScroll(mistakes(d.mistakes()))));
            tabs.getTabs().add(new Tab("Practice quizzes (" + d.practice().size() + ")", Ui.tabScroll(practice(d.practice()))));
            tabs.getTabs().add(new Tab("Revision history", Ui.tabScroll(history(d.history()))));
            root.getChildren().setAll(Ui.pageFixed(Ui.pageHeader("Revision",
                    "Go back over finished lectures and your mistakes. Practice never changes official marks."), tabs));
        });
        return root;
    }

    private Node lectures(User u, List<Lecture> lectures, List<ProgressDao.RevisionRow> history) {
        LocalDate today = LocalDate.now(ctx.clock());
        Set<Long> revisedToday = history.stream().filter(h -> h.revisedOn().equals(today))
                .map(ProgressDao.RevisionRow::topicId).collect(Collectors.toSet());
        VBox list = new VBox(10);
        if (lectures.isEmpty()) {
            list.getChildren().add(Ui.emptyState(Icons.REVISION, "No completed lectures yet", null, null));
        }
        for (Lecture l : lectures) {
            VBox text = new VBox(4, Ui.label(l.courseCode() + " · L" + l.lectureNo() + " · " + l.topic(), "body-strong"),
                    Ui.label("Unit " + l.unitNo() + " · " + l.startsAt().atZone(ctx.lectures().zone()).format(Ui.DATE), "small", "muted"));
            if (!l.objectives().isBlank()) {
                text.getChildren().add(Ui.wrapping("Key concepts: " + l.objectives().replace("\n", " · "), "small"));
            }
            HBox.setHgrow(text, Priority.ALWAYS);
            text.setMinWidth(0);
            Button open = Ui.button("Open", Icons.BOOK_OPEN, "btn-ghost");
            open.setOnAction(e -> shell.open(new LectureDetailPage(ctx, shell, l.id())));
            HBox row = Ui.row(12, Icons.of(Icons.REVISION, 20), text, open);
            if (l.topicId() != null) {
                if (revisedToday.contains(l.topicId())) {
                    row.getChildren().add(Ui.badge("Revised today", "success"));
                } else {
                    Button mark = Ui.button("Mark topic revised", Icons.CHECK);
                    mark.setOnAction(e -> ctx.background().run(() -> ctx.progress().markRevised(u, l.topicId(), ""), added -> {
                        shell.toast().success(added ? "Marked revised. Keep the streak going!" : "Already marked today.");
                        shell.refresh();
                    }, shell::showError));
                    row.getChildren().add(mark);
                }
            }
            row.getStyleClass().add("list-row");
            list.getChildren().add(row);
        }
        return list;
    }

    private Node mistakes(List<ProgressDao.MistakeRow> rows) {
        VBox list = new VBox(12);
        if (rows.isEmpty()) {
            list.getChildren().add(Ui.emptyState(Icons.TROPHY, "Nothing to revise here",
                    "Questions you got wrong appear once your professor releases the results.", null));
        }
        for (ProgressDao.MistakeRow m : rows) {
            VBox card = Ui.card(Ui.row(6, Ui.badge(m.courseCode(), "primary"), Ui.label(m.quizTitle(), "small", "muted"),
                            m.topic() == null ? Ui.label("") : Ui.badge(m.topic(), "info")),
                    Ui.wrapping(m.prompt(), "h3"),
                    Ui.wrapping("Your answer: " + (m.chosen() == null ? "(not answered)" : m.chosen()), "error-text"),
                    Ui.wrapping("Correct answer: " + m.correct(), "success-text"));
            if (!m.explanation().isBlank()) {
                card.getChildren().add(Ui.wrapping("Why: " + m.explanation(), "muted"));
            }
            list.getChildren().add(card);
        }
        return list;
    }

    private Node practice(List<StudentQuiz> quizzes) {
        VBox list = new VBox(10);
        if (quizzes.isEmpty()) {
            list.getChildren().add(Ui.emptyState(Icons.QUIZ, "No practice quizzes yet",
                    "Quizzes whose results are released (and that allow practice) can be re-attempted here.", null));
        }
        for (StudentQuiz sq : quizzes) {
            list.getChildren().add(QuizWidgets.studentRow(ctx, shell, sq));
        }
        return list;
    }

    private Node history(List<ProgressDao.RevisionRow> rows) {
        VBox list = new VBox(8);
        if (rows.isEmpty()) {
            list.getChildren().add(Ui.label("You have not marked any topic revised yet.", "faint"));
        }
        for (ProgressDao.RevisionRow r : rows) {
            HBox row = Ui.row(10, Ui.badge(r.courseCode(), "primary"), Ui.label("Unit " + r.unitNo() + " · " + r.topic(), "body-strong"),
                    Ui.spacer(), Ui.label(r.revisedOn().format(Ui.DATE), "small", "muted"));
            row.getStyleClass().add("list-row");
            list.getChildren().add(row);
        }
        return list;
    }
}
