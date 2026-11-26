package com.classsync.view;

import com.classsync.AppContext;
import com.classsync.dao.DiscussionDao;
import com.classsync.model.Course;
import com.classsync.model.Discussion;
import com.classsync.model.User;
import com.classsync.service.DiscussionService;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;

/** Doubt forum: search and filter doubts across courses, ask a new one, and see the Doubt Heatmap. */
public class DiscussionsPage extends Page {

    public DiscussionsPage(AppContext ctx, AppShell shell) {
        super(ctx, shell);
    }

    @Override
    public Nav nav() {
        return Nav.DISCUSSIONS;
    }

    @Override
    public Node build() {
        User u = user();
        ComboBox<Object> course = new ComboBox<>();
        course.getItems().add("All courses");
        course.setValue("All courses");
        course.setConverter(QuizForms.converter(o -> o instanceof Course c ? c.display() : o.toString()));
        TextField search = new TextField();
        search.setPromptText("Search doubts");
        CheckBox unresolved = new CheckBox("Unresolved only");
        CheckBox mine = new CheckBox("Asked by me");
        VBox list = new VBox(10, Ui.loading("Loading doubts…"));
        VBox heat = new VBox(10);
        Button ask = Ui.primary("Ask a doubt", Icons.PLUS);
        java.util.concurrent.atomic.AtomicReference<List<Course>> courses = new java.util.concurrent.atomic.AtomicReference<>(List.of());

        Runnable reload = () -> {
            Long courseId = course.getValue() instanceof Course c ? c.id() : null;
            var q = new DiscussionService.Query(courseId, null, search.getText(), unresolved.isSelected(), mine.isSelected(), 200);
            load(() -> ctx.discussions().list(u, q), items -> {
                list.getChildren().clear();
                if (items.isEmpty()) {
                    list.getChildren().add(Ui.emptyState(Icons.CHAT, "No doubts here", "Ask the first question for your class.", null));
                }
                for (Discussion d : items) {
                    list.getChildren().add(row(ctx, shell, d));
                }
            });
            Course heatCourse = course.getValue() instanceof Course c ? c : courses.get().isEmpty() ? null : courses.get().get(0);
            if (heatCourse != null) {
                load(() -> ctx.discussions().heatmap(u, heatCourse.id()), rows -> heat.getChildren().setAll(heatmap(heatCourse, rows)));
            }
        };
        course.setOnAction(e -> reload.run());
        search.setOnAction(e -> reload.run());
        unresolved.setOnAction(e -> reload.run());
        mine.setOnAction(e -> reload.run());
        ask.setOnAction(e -> CollabForms.askDoubt(ctx, shell, courses.get(),
                course.getValue() instanceof Course c ? c.id() : null, null,
                id -> shell.open(new DiscussionThreadPage(ctx, shell, id))));
        load(() -> ctx.courses().myCourses(u, false), mineCourses -> {
            courses.set(mineCourses);
            course.getItems().addAll(mineCourses);
            reload.run();
        });

        VBox left = new VBox(12, Ui.row(8, search, course, unresolved, mine), list);
        HBox.setHgrow(left, Priority.ALWAYS);
        heat.setPrefWidth(360);
        heat.setMinWidth(300);
        return Ui.page(new VBox(16, Ui.pageHeader("Discussions",
                "Course doubts. Unresolved ones come first; professor answers are marked.", ask), new HBox(20, left, heat)));
    }

    /** One doubt in a list; shared with the lecture Doubts tab and the dashboard. */
    static HBox row(AppContext ctx, AppShell shell, Discussion d) {
        HBox tags = Ui.row(6, Ui.badge(d.courseCode(), "primary"));
        if (d.lectureNo() != null) {
            tags.getChildren().add(Ui.badge("L" + d.lectureNo(), "neutral"));
        }
        if (d.topicName() != null) {
            tags.getChildren().add(Ui.badge(d.topicName(), "info"));
        }
        if (d.hidden()) {
            tags.getChildren().add(Ui.badge("Hidden", "danger"));
        }
        VBox text = new VBox(4, Ui.wrapping(d.title(), "body-strong"), tags,
                Ui.label(d.authorName() + " · " + d.createdAt().atZone(ctx.lectures().zone()).format(Ui.DATE_TIME)
                        + " · " + QuizWidgets.plural(d.replyCount(), "reply", "replies"), "small", "muted"));
        HBox.setHgrow(text, Priority.ALWAYS);
        text.setMinWidth(0);
        HBox row = new HBox(12, Icons.of(Icons.CHAT, 20), text);
        if (d.professorAnswered()) {
            row.getChildren().add(Ui.badge("Professor answered", "info"));
        }
        row.getChildren().add(d.resolved() ? Ui.badge("Resolved", "success") : Ui.badge("Open", "warning"));
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().addAll("list-row", "list-row-clickable");
        Ui.clickable(row, () -> shell.open(new DiscussionThreadPage(ctx, shell, d.id())));
        row.getStyleClass().remove("card-clickable");
        return row;
    }

    /**
     * Doubt Heatmap: one cell per syllabus topic, shaded by its number of unresolved doubts
     * (darkest = most unresolved). Plain SQL counts, drawn with JavaFX regions.
     */
    static VBox heatmap(Course c, List<DiscussionDao.HeatRow> rows) {
        FlowPane cells = new FlowPane(8, 8);
        int max = rows.stream().mapToInt(DiscussionDao.HeatRow::unresolved).max().orElse(0);
        for (DiscussionDao.HeatRow r : rows) {
            double intensity = max == 0 ? 0 : (double) r.unresolved() / max;
            VBox cell = new VBox(2, Ui.label(r.topic(), "body-strong"),
                    Ui.label(r.unresolved() + " unresolved · " + r.total() + " total", "small"));
            cell.getStyleClass().add("heat-cell");
            cell.setStyle("-fx-background-color: rgba(198, 22, 28, " + (0.08 + 0.55 * intensity) + ");");
            cell.setPrefWidth(160);
            cells.getChildren().add(cell);
        }
        VBox card = Ui.card("Doubt heatmap · " + c.code(),
                rows.isEmpty() ? Ui.label("No doubts yet in this course.", "faint") : cells,
                Ui.wrapping("Darker = more unresolved doubts on that topic. Topics come from the lecture a doubt is linked to.",
                        "small", "faint"));
        return card;
    }
}
