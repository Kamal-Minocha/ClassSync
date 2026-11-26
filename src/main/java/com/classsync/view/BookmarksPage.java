package com.classsync.view;

import com.classsync.AppContext;
import com.classsync.dao.ProgressDao;
import com.classsync.dao.StudyDao;
import com.classsync.model.Course;
import com.classsync.model.User;
import com.classsync.service.StudyService;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.time.LocalDate;
import java.util.List;

/** Private study space: bookmarks, personal revision tasks, recent activity and revision history. */
public class BookmarksPage extends Page {

    public BookmarksPage(AppContext ctx, AppShell shell) {
        super(ctx, shell);
    }

    @Override
    public Nav nav() {
        return Nav.BOOKMARKS;
    }

    @Override
    public Node build() {
        User u = user();
        VBox body = new VBox(20, Ui.pageHeader("Bookmarks & tasks", "Only you can see these."), Ui.loading("Loading…"));
        record Data(List<StudyService.Bookmark> bookmarks, List<StudyDao.TaskRow> tasks, List<Course> courses,
                    List<ProgressDao.ActivityRow> activity, List<ProgressDao.RevisionRow> revisions) { }
        load(() -> new Data(ctx.study().bookmarks(u), ctx.study().tasks(u), ctx.courses().myCourses(u, false),
                ctx.progress().recentActivity(u, 15), u.role().isLearner() ? ctx.progress().revisions(u, 15) : List.of()), d -> {
            body.getChildren().remove(1);
            VBox tasks = tasksCard(u, d.tasks(), d.courses());
            VBox marks = bookmarksCard(u, d.bookmarks());
            HBox.setHgrow(tasks, Priority.ALWAYS);
            HBox.setHgrow(marks, Priority.ALWAYS);
            tasks.setPrefWidth(520);
            marks.setPrefWidth(520);
            VBox activity = new VBox(6);
            if (d.activity().isEmpty()) {
                activity.getChildren().add(Ui.label("No activity yet.", "faint"));
            }
            for (ProgressDao.ActivityRow a : d.activity()) {
                HBox row = Ui.row(10, Ui.label(a.label(), "small"), Ui.spacer(),
                        Ui.label(a.at().atZone(ctx.lectures().zone()).format(Ui.DATE_TIME), "small", "faint"));
                row.getStyleClass().add("list-row");
                activity.getChildren().add(row);
            }
            VBox revisions = new VBox(6);
            if (d.revisions().isEmpty()) {
                revisions.getChildren().add(Ui.label("Mark topics revised from the Revision page.", "faint"));
            }
            for (ProgressDao.RevisionRow r : d.revisions()) {
                HBox row = Ui.row(10, Ui.badge(r.courseCode(), "primary"), Ui.label(r.topic(), "small"), Ui.spacer(),
                        Ui.label(r.revisedOn().format(Ui.DATE), "small", "faint"));
                row.getStyleClass().add("list-row");
                revisions.getChildren().add(row);
            }
            VBox activityCard = Ui.card("Recent activity", activity);
            VBox revisionCard = Ui.card("Revision history", revisions);
            HBox.setHgrow(activityCard, Priority.ALWAYS);
            HBox.setHgrow(revisionCard, Priority.ALWAYS);
            body.getChildren().addAll(new HBox(20, tasks, marks), new HBox(20, activityCard, revisionCard));
        });
        return Ui.page(body);
    }

    private VBox tasksCard(User u, List<StudyDao.TaskRow> tasks, List<Course> courses) {
        TextField title = new TextField();
        title.setPromptText("e.g. Solve 5 normalization exercises");
        DatePicker due = new DatePicker();
        due.setPromptText("Due (optional)");
        due.setPrefWidth(150);
        ComboBox<Object> course = new ComboBox<>();
        course.getItems().add("No course");
        course.getItems().addAll(courses);
        course.setValue("No course");
        course.setConverter(QuizForms.converter(o -> o instanceof Course c ? c.code() : o.toString()));
        Button add = Ui.primary("Add", Icons.PLUS);
        add.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        course.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        Runnable submit = () -> {
            if (title.getText().isBlank()) {
                shell.toast().error("Write the task first.");
                return;
            }
            Long courseId = course.getValue() instanceof Course c ? c.id() : null;
            String t = title.getText();
            LocalDate d = due.getValue();
            ctx.background().run(() -> ctx.study().addTask(u, t, d, courseId), id -> shell.refresh(), shell::showError);
        };
        add.setOnAction(e -> submit.run());
        title.setOnAction(e -> submit.run());
        HBox.setHgrow(title, Priority.ALWAYS);
        VBox list = new VBox(6);
        if (tasks.isEmpty()) {
            list.getChildren().add(Ui.label("No tasks yet. Plan your revision here.", "faint"));
        }
        LocalDate today = LocalDate.now(ctx.clock());
        for (StudyDao.TaskRow t : tasks) {
            CheckBox done = new CheckBox(t.title());
            done.setSelected(t.doneAt() != null);
            done.setWrapText(true);
            done.setOnAction(e -> ctx.background().run(() -> {
                ctx.study().setTaskDone(u, t.id(), done.isSelected());
                return null;
            }, r -> shell.refresh(), shell::showError));
            HBox.setHgrow(done, Priority.ALWAYS);
            HBox row = Ui.row(10, done);
            if (t.courseCode() != null) {
                row.getChildren().add(Ui.badge(t.courseCode(), "primary"));
            }
            if (t.dueDate() != null) {
                boolean late = t.doneAt() == null && t.dueDate().isBefore(today);
                row.getChildren().add(Ui.badge((late ? "Overdue · " : "Due ") + t.dueDate().format(Ui.DATE), late ? "danger" : "neutral"));
            }
            Button delete = Ui.iconButton(Icons.X, "Delete task");
            delete.setOnAction(e -> ctx.background().run(() -> {
                ctx.study().deleteTask(u, t.id());
                return null;
            }, r -> shell.refresh(), shell::showError));
            row.getChildren().add(delete);
            row.getStyleClass().add("list-row");
            if (t.doneAt() != null) {
                row.setOpacity(0.6);
            }
            list.getChildren().add(row);
        }
        return Ui.card("Study tasks", Ui.row(8, title, due, course, add), list);
    }

    private VBox bookmarksCard(User u, List<StudyService.Bookmark> marks) {
        VBox list = new VBox(8);
        if (marks.isEmpty()) {
            list.getChildren().add(Ui.label("Bookmark materials, notes, lectures and doubts to find them here.", "faint"));
        }
        for (StudyService.Bookmark b : marks) {
            String icon = switch (b.target()) {
                case MATERIAL -> Icons.FILE;
                case LECTURE -> Icons.CALENDAR;
                case DISCUSSION -> Icons.CHAT;
            };
            VBox text = new VBox(2, Ui.label(b.title(), "body-strong"), Ui.label(b.subtitle(), "small", "muted"));
            HBox.setHgrow(text, Priority.ALWAYS);
            text.setMinWidth(0);
            Button open = Ui.button("Open", Icons.EYE, "btn-ghost");
            open.setOnAction(e -> {
                switch (b.target()) {
                    case MATERIAL -> Widgets.openFile(ctx, shell, b.material());
                    case LECTURE -> shell.open(new LectureDetailPage(ctx, shell, b.targetId()));
                    case DISCUSSION -> shell.open(new DiscussionThreadPage(ctx, shell, b.targetId()));
                }
            });
            Button remove = Ui.iconButton(Icons.X, "Remove bookmark");
            remove.setOnAction(e -> ctx.background().run(() -> {
                ctx.study().bookmark(u, b.target(), b.targetId(), false);
                return null;
            }, r -> shell.refresh(), shell::showError));
            HBox row = Ui.row(10, Icons.of(icon, 18), text, open, remove);
            row.getStyleClass().add("list-row");
            list.getChildren().add(row);
        }
        return Ui.card("Bookmarks", list);
    }
}
