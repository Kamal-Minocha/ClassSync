package com.classsync.view;

import com.classsync.AppContext;
import com.classsync.model.CoursePermission;
import com.classsync.model.Lecture;
import com.classsync.model.LectureStatus;
import com.classsync.model.Material;
import com.classsync.model.User;
import com.classsync.service.Errors;
import com.classsync.service.MaterialService;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.time.ZonedDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * One lecture as a complete learning unit: preview/preparation (before), materials (during),
 * and the after-lecture activities that later modules add as tabs.
 */
public class LectureDetailPage extends Page {

    private final long lectureId;
    private Lecture lecture;
    private Set<CoursePermission> perms = EnumSet.noneOf(CoursePermission.class);
    private boolean courseProfessor;

    public LectureDetailPage(AppContext ctx, AppShell shell, long lectureId) {
        super(ctx, shell);
        this.lectureId = lectureId;
    }

    @Override
    public Nav nav() {
        return Nav.SCHEDULE;
    }

    @Override
    public Node build() {
        User u = user();
        javafx.scene.layout.StackPane root = new javafx.scene.layout.StackPane(Ui.loading("Loading lecture…"));
        record Data(Lecture l, Set<CoursePermission> p, boolean prof) { }
        load(() -> {
            Lecture l = ctx.lectures().get(u, lectureId);
            Set<CoursePermission> p = ctx.courses().myPermissions(u, l.courseId());
            return new Data(l, p, p.contains(CoursePermission.UPDATE_LECTURES) && u.role() == com.classsync.model.Role.PROFESSOR);
        }, d -> {
            lecture = d.l();
            perms = d.p();
            courseProfessor = d.prof();
            root.getChildren().setAll(Ui.pageFixed(header(u), tabs(u)));
        }, e -> root.getChildren().setAll(Ui.errorState(Errors.userMessage(e), null)));
        return root;
    }

    private Node header(User u) {
        var zone = ctx.lectures().zone();
        ZonedDateTime start = lecture.startsAt().atZone(zone);
        ZonedDateTime end = lecture.endsAt().atZone(zone);
        Button back = Ui.button("Course", Icons.CHEVRON_LEFT, "btn-ghost");
        back.setOnAction(e -> shell.open(new CourseDetailPage(ctx, shell, lecture.courseId())));

        HBox tags = new HBox(6, Ui.badge(lecture.courseCode(), "primary"), Ui.badge("Lecture " + lecture.lectureNo(), "neutral"),
                Ui.badge("Unit " + lecture.unitNo(), "neutral"), Widgets.statusBadge(lecture.status()));
        VBox text = new VBox(8, tags, Ui.wrapping(lecture.topic(), "h1"),
                Ui.row(16, Ui.row(6, Icons.of(Icons.CALENDAR, 16),Ui.label(start.format(Ui.DATE), "muted")),
                        Ui.row(6, Icons.of(Icons.CLOCK, 16), Ui.label(start.format(Ui.TIME) + " – " + end.format(Ui.TIME), "muted")),
                        Ui.label(lecture.room().isBlank() ? "" : "Room " + lecture.room(), "muted")));

        HBox actions = new HBox(8);
        if (u.role().isLearner()) {
            actions.getChildren().add(StudyWidgets.bookmark(ctx, shell, com.classsync.dao.StudyDao.Target.LECTURE, lecture.id(), null));
        }
        boolean editable = lecture.status() == LectureStatus.DRAFT || lecture.status() == LectureStatus.PUBLISHED;
        if (perms.contains(CoursePermission.UPDATE_LECTURES) && editable) {
            Button edit = Ui.button("Edit", Icons.NOTE);
            edit.setOnAction(e -> ctx.background().run(() -> ctx.courses().topics(u, lecture.courseId()),
                    topics -> Forms.lecture(ctx, shell, lecture.courseId(), lecture, topics, shell::refresh), shell::showError));
            actions.getChildren().add(edit);
            if (lecture.status() == LectureStatus.DRAFT) {
                actions.getChildren().add(statusButton(u, "Publish preview", LectureStatus.PUBLISHED, true));
            } else if (lecture.startsAt().isAfter(ctx.clock().instant())) {
                actions.getChildren().add(statusButton(u, "Move back to draft", LectureStatus.DRAFT, false));
            }
        }
        if (courseProfessor) {
            if (lecture.status() == LectureStatus.PUBLISHED) {
                actions.getChildren().add(statusButton(u, "Mark completed", LectureStatus.COMPLETED, false));
            }
            if (lecture.status() != LectureStatus.CANCELLED && lecture.status() != LectureStatus.COMPLETED) {
                actions.getChildren().add(statusButton(u, "Cancel lecture", LectureStatus.CANCELLED, false));
            }
            if (lecture.status() == LectureStatus.DRAFT) {
                Button delete = Ui.button("Delete draft", Icons.X, "btn-ghost");
                delete.setOnAction(e -> {
                    if (Dialogs.confirm(shell.window(), "Delete draft", "Delete this draft lecture permanently?", "Delete")) {
                        ctx.background().run(() -> {
                            ctx.lectures().deleteDraft(u, lectureId);
                            return null;
                        }, r -> shell.open(new CourseDetailPage(ctx, shell, lecture.courseId())), shell::showError);
                    }
                });
                actions.getChildren().add(delete);
            }
        }
        HBox banner = new HBox(16, text, Ui.spacer(), actions);
        banner.getStyleClass().add("hero-content");
        // the lecture's course photo, as on the course page
        return new VBox(8, back, Ui.photoBanner(Images.forCourse(lecture.courseId()), "banner-overlay", 18, false, banner));
    }

    private Button statusButton(User u, String text, LectureStatus next, boolean primary) {
        Button b = primary ? Ui.primary(text, Icons.CHECK) : Ui.button(text, null, "btn-ghost");
        b.setOnAction(e -> {
            if (next == LectureStatus.CANCELLED && !Dialogs.confirm(shell.window(), "Cancel lecture",
                    "Students will see this lecture as cancelled. Cancelled lectures never count toward attendance.", "Cancel lecture")) {
                return;
            }
            ctx.background().run(() -> {
                ctx.lectures().changeStatus(u, lectureId, next);
                return null;
            }, r -> {
                shell.toast().success("Lecture is now " + next.label().toLowerCase() + ".");
                shell.refresh();
            }, shell::showError);
        });
        return b;
    }

    private Node tabs(User u) {
        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getTabs().add(new Tab("Overview", Ui.tabScroll(overview())));
        tabs.getTabs().add(new Tab("Materials", Ui.tabScroll(materials(u))));
        LectureTabs.addModuleTabs(ctx, shell, u, lecture, perms, tabs);
        return tabs;
    }

    private Node overview() {
        VBox before = Ui.card("Before the lecture",
                section("Preparation", lecture.preparation(), "No preparation instructions."),
                section("Learning objectives", lecture.objectives(), "No objectives listed."));
        VBox about = Ui.card("About this lecture", section(null, lecture.description(), "No description."));
        HBox row = new HBox(18, before, about);
        before.setPrefWidth(520);
        HBox.setHgrow(about, javafx.scene.layout.Priority.ALWAYS);
        HBox.setHgrow(before, javafx.scene.layout.Priority.ALWAYS);
        return row;
    }

    private static Node section(String title, String text, String empty) {
        VBox v = new VBox(6);
        if (title != null) {
            v.getChildren().add(Ui.label(title, "field-label"));
        }
        if (text == null || text.isBlank()) {
            v.getChildren().add(Ui.label(empty, "faint"));
        } else {
            for (String line : text.split("\\R")) {
                if (!line.isBlank()) {
                    v.getChildren().add(Ui.wrapping((text.contains("\n") ? "• " : "") + line.trim()));
                }
            }
        }
        return v;
    }

    private Node materials(User u) {
        VBox list = new VBox(8, Ui.loading("Loading materials…"));
        HBox bar = new HBox(8);
        if (perms.contains(CoursePermission.UPLOAD_MATERIALS)) {
            Button upload = Ui.primary("Upload material", Icons.UPLOAD);
            upload.setOnAction(e -> ctx.background().run(() -> ctx.courses().get(u, lecture.courseId()),
                    c -> Forms.upload(ctx, shell, List.of(c), c.id(), lectureId, id -> shell.refresh()), shell::showError));
            bar.getChildren().add(upload);
        }
        load(() -> ctx.materials().list(u, new MaterialService.Query(lecture.courseId(), lectureId, null, null, "newest", 100)),
                items -> {
                    list.getChildren().clear();
                    if (items.isEmpty()) {
                        list.getChildren().add(Ui.emptyState(Icons.FILE, "No materials for this lecture yet", null, null));
                    }
                    for (Material m : items) {
                        list.getChildren().add(Widgets.materialRow(ctx, shell, m));
                    }
                });
        return new VBox(12, bar, list);
    }
}
