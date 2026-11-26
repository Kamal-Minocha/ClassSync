package com.classsync.view;

import com.classsync.AppContext;
import com.classsync.dao.NoteDao;
import com.classsync.model.Course;
import com.classsync.model.Material;
import com.classsync.model.ReviewStatus;
import com.classsync.model.Role;
import com.classsync.model.User;
import com.classsync.service.NoteService;
import javafx.collections.FXCollections;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Set;

/**
 * Student notes: approved notes from classmates (ranked by upvotes), the student's own submissions with
 * review status, and — for the professor and delegated CRs — the review queue.
 */
public class NotesPage extends Page {

    public NotesPage(AppContext ctx, AppShell shell) {
        super(ctx, shell);
    }

    @Override
    public Nav nav() {
        return Nav.NOTES;
    }

    @Override
    public Node build() {
        User u = user();
        StackPane root = new StackPane(Ui.loading("Loading notes…"));
        record Data(List<Course> courses, List<Material> queue, boolean reviewer) { }
        load(() -> {
            List<Course> courses = ctx.courses().myCourses(u, false);
            boolean reviewer = false;
            for (Course c : courses) {
                reviewer |= ctx.courses().myPermissions(u, c.id()).contains(com.classsync.model.CoursePermission.REVIEW_NOTES);
            }
            return new Data(courses, ctx.notes().reviewQueue(u), reviewer);
        }, d -> {
            Button submit = Ui.primary("Submit notes", Icons.UPLOAD);
            submit.setOnAction(e -> CollabForms.submitNote(ctx, shell, d.courses(), null, null, shell::refresh));
            boolean learner = u.role().isLearner();
            submit.setVisible(learner);
            submit.setManaged(learner);
            TabPane tabs = new TabPane();
            tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
            tabs.getTabs().add(new Tab("Approved notes", Ui.tabScroll(approved(u, d.courses()))));
            if (learner) {
                tabs.getTabs().add(new Tab("My submissions", Ui.tabScroll(mine(u))));
            }
            if (d.reviewer()) {
                Tab queue = new Tab("Review queue (" + d.queue().size() + ")", Ui.tabScroll(queue(u, d.queue())));
                tabs.getTabs().add(queue);
            }
            root.getChildren().setAll(Ui.pageFixed(Ui.pageHeader("Notes",
                    "Notes shared by students. Classmates only see notes after a reviewer approves them.", submit), tabs));
        });
        return root;
    }

    // ---------------------------------------------------------------- approved

    private Node approved(User u, List<Course> courses) {
        ComboBox<Object> course = new ComboBox<>();
        course.getItems().add("All courses");
        course.getItems().addAll(courses);
        course.setValue("All courses");
        course.setConverter(QuizForms.converter(o -> o instanceof Course c ? c.display() : o.toString()));
        ComboBox<String> sort = new ComboBox<>(FXCollections.observableArrayList("Most helpful", "Newest", "Most downloaded"));
        sort.setValue("Most helpful");
        TextField search = new TextField();
        search.setPromptText("Search notes");
        VBox list = new VBox(10);
        Runnable reload = () -> {
            Long courseId = course.getValue() instanceof Course c ? c.id() : null;
            String key = switch (sort.getValue()) {
                case "Newest" -> "newest";
                case "Most downloaded" -> "downloads";
                default -> "votes";
            };
            list.getChildren().setAll(Ui.loading("Loading notes…"));
            record Data(List<Material> notes, Set<Long> votes) { }
            load(() -> new Data(ctx.notes().approved(u, courseId, null, search.getText(), key), ctx.notes().myVotes(u)), d -> {
                list.getChildren().clear();
                if (d.notes().isEmpty()) {
                    list.getChildren().add(Ui.emptyState(Icons.NOTE, "No approved notes yet",
                            "Approved notes from classmates appear here.", null));
                }
                for (Material m : d.notes()) {
                    list.getChildren().add(noteRow(u, m, d.votes().contains(m.id())));
                }
            });
        };
        course.setOnAction(e -> reload.run());
        sort.setOnAction(e -> reload.run());
        search.setOnAction(e -> reload.run());
        reload.run();
        return new VBox(12, Ui.row(8, search, course, Ui.label("Sort", "field-label"), sort), list);
    }

    /** Approved note row; shared with the lecture Notes tab. */
    HBox noteRow(User u, Material m, boolean voted) {
        return noteRow(ctx, shell, u, m, voted);
    }

    static HBox noteRow(AppContext ctx, AppShell shell, User u, Material m, boolean voted) {
        VBox text = new VBox(3, Ui.row(6, Ui.label(m.title(), "body-strong"),
                        m.recommended() ? Ui.badge("Recommended", "success") : Ui.label("")),
                Ui.label(m.courseCode() + (m.lectureNo() == null ? "" : " · L" + m.lectureNo()) + " · by " + m.uploaderName()
                        + " · " + QuizWidgets.plural(m.downloadCount(), "download"), "small", "muted"));
        HBox.setHgrow(text, Priority.ALWAYS);
        text.setMinWidth(0);
        Button vote = Ui.button(String.valueOf(m.votes()), Icons.THUMB_UP, voted ? "btn-primary" : "btn-ghost");
        vote.setAccessibleText((voted ? "Remove upvote" : "Upvote") + " " + m.title());
        vote.setDisable(m.uploaderId() == u.id() || !u.role().isLearner());
        vote.setOnAction(e -> ctx.background().run(() -> {
            ctx.notes().vote(u, m.id(), !voted);
            return null;
        }, r -> shell.refresh(), shell::showError));
        HBox row = new HBox(12, Widgets.fileTypeChip(m.fileType()), text, vote, Widgets.fileActions(ctx, shell, m));
        if (u.role() == Role.PROFESSOR) {
            Button rec = Ui.button(m.recommended() ? "Unrecommend" : "Recommend", Icons.STAR, "btn-ghost");
            rec.setOnAction(e -> ctx.background().run(() -> {
                ctx.materials().setRecommended(u, m.id(), !m.recommended());
                return null;
            }, r -> shell.refresh(), shell::showError));
            row.getChildren().add(rec);
        }
        row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        row.getStyleClass().add("list-row");
        return row;
    }

    // ---------------------------------------------------------------- mine

    private Node mine(User u) {
        VBox list = new VBox(10, Ui.loading("Loading your notes…"));
        load(() -> ctx.notes().mine(u), items -> {
            list.getChildren().clear();
            if (items.isEmpty()) {
                list.getChildren().add(Ui.emptyState(Icons.NOTE, "You have not submitted notes yet",
                        "Share your lecture notes — once approved, classmates can download and upvote them.", null));
            }
            for (NoteService.MyNote n : items) {
                list.getChildren().add(myRow(u, n));
            }
        });
        return list;
    }

    static Label statusBadge(ReviewStatus s) {
        return switch (s) {
            case PENDING -> Ui.badge("Pending review", "warning");
            case APPROVED -> Ui.badge("Approved", "success");
            case REJECTED -> Ui.badge("Needs changes", "danger");
        };
    }

    private Node myRow(User u, NoteService.MyNote n) {
        Material m = n.note();
        VBox text = new VBox(3, Ui.label(m.title(), "body-strong"),
                Ui.label(m.courseCode() + (m.lectureNo() == null ? "" : " · L" + m.lectureNo()) + " · " + m.originalFilename()
                        + (m.published() ? " · " + QuizWidgets.plural(m.votes(), "upvote") : ""), "small", "muted"));
        if (m.reviewStatus() == ReviewStatus.REJECTED && !n.lastComment().isBlank()) {
            text.getChildren().add(Ui.wrapping("Reviewer: “" + n.lastComment() + "”", "small", "error-text"));
        }
        HBox.setHgrow(text, Priority.ALWAYS);
        text.setMinWidth(0);
        Button history = Ui.button("History", Icons.CLOCK, "btn-ghost");
        history.setOnAction(e -> showHistory(u, m));
        HBox row = Ui.row(12, Widgets.fileTypeChip(m.fileType()), text, statusBadge(m.reviewStatus()), history);
        if (m.reviewStatus() != ReviewStatus.APPROVED) {
            Button resubmit = Ui.button(m.reviewStatus() == ReviewStatus.REJECTED ? "Fix and resubmit" : "Edit", Icons.UPLOAD);
            resubmit.setOnAction(e -> CollabForms.resubmitNote(ctx, shell, m, n.lastComment(), shell::refresh));
            row.getChildren().add(resubmit);
        }
        row.getChildren().add(Widgets.fileActions(ctx, shell, m));
        row.getStyleClass().add("list-row");
        return row;
    }

    private void showHistory(User u, Material m) {
        ctx.background().run(() -> ctx.notes().history(u, m.id()), rows -> {
            StringBuilder sb = new StringBuilder();
            for (NoteDao.HistoryRow h : rows) {
                sb.append(h.createdAt().atZone(ctx.lectures().zone()).format(Ui.DATE_TIME)).append(" — ")
                        .append(h.action().charAt(0)).append(h.action().substring(1).toLowerCase())
                        .append(" by ").append(h.actorName());
                if (!h.comment().isBlank()) {
                    sb.append(": “").append(h.comment()).append("”");
                }
                sb.append('\n');
            }
            Dialogs.info(shell.window(), "Review history · " + m.title(), sb.toString().strip());
        }, shell::showError);
    }

    // ---------------------------------------------------------------- review queue

    private Node queue(User u, List<Material> items) {
        VBox list = new VBox(10);
        if (items.isEmpty()) {
            list.getChildren().add(Ui.emptyState(Icons.CHECK, "Nothing to review", "New submissions from students appear here.", null));
        }
        for (Material m : items) {
            VBox text = new VBox(3, Ui.label(m.title(), "body-strong"),
                    Ui.label(m.courseCode() + (m.lectureNo() == null ? "" : " · L" + m.lectureNo()) + " · by " + m.uploaderName()
                            + " · " + m.originalFilename(), "small", "muted"));
            if (!m.description().isBlank()) {
                text.getChildren().add(Ui.wrapping(m.description(), "small"));
            }
            HBox.setHgrow(text, Priority.ALWAYS);
            text.setMinWidth(0);
            Button approve = Ui.primary("Approve", Icons.CHECK);
            approve.setOnAction(e -> ctx.background().run(() -> {
                ctx.notes().approve(u, m.id());
                return null;
            }, r -> {
                shell.toast().success("Approved — classmates can now see it.");
                shell.refresh();
            }, shell::showError));
            Button reject = Ui.button("Request changes", Icons.X, "btn-ghost");
            reject.setOnAction(e -> Dialogs.askText(shell.window(), "Request changes",
                    "Tell " + m.uploaderName() + " what to fix (they will see this).", 1000).ifPresent(reason ->
                    ctx.background().run(() -> {
                        ctx.notes().reject(u, m.id(), reason);
                        return null;
                    }, r -> {
                        shell.toast().success("Sent back to the author.");
                        shell.refresh();
                    }, shell::showError)));
            HBox row = Ui.row(12, Widgets.fileTypeChip(m.fileType()), text, Widgets.fileActions(ctx, shell, m), reject, approve);
            row.getStyleClass().add("list-row");
            list.getChildren().add(row);
        }
        return list;
    }
}
