package com.classsync.view;

import com.classsync.AppContext;
import com.classsync.model.Course;
import com.classsync.model.CoursePermission;
import com.classsync.model.EnrolledStudent;
import com.classsync.model.Lecture;
import com.classsync.model.Material;
import com.classsync.model.Role;
import com.classsync.model.StaffMember;
import com.classsync.model.Topic;
import com.classsync.model.User;
import com.classsync.service.MaterialService;
import javafx.beans.property.SimpleStringProperty;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Course detail: lectures, materials, and (for the professor) students, CR delegation and
 * syllabus topics. Every tab loads through services that re-check access.
 */
public class CourseDetailPage extends Page {

    private final long courseId;
    private Course course;
    private Set<CoursePermission> perms = EnumSet.noneOf(CoursePermission.class);
    private List<Topic> topics = List.of();

    public CourseDetailPage(AppContext ctx, AppShell shell, long courseId) {
        super(ctx, shell);
        this.courseId = courseId;
    }

    @Override
    public Nav nav() {
        return Nav.COURSES;
    }

    @Override
    public Node build() {
        User u = user();
        javafx.scene.layout.StackPane root = new javafx.scene.layout.StackPane(Ui.loading("Loading course…"));
        record Data(Course c, Set<CoursePermission> p, List<Topic> t) { }
        load(() -> new Data(ctx.courses().get(u, courseId), ctx.courses().myPermissions(u, courseId),
                ctx.courses().topics(u, courseId)), d -> {
            course = d.c();
            perms = d.p();
            topics = d.t();
            root.getChildren().setAll(Ui.pageFixed(header(u), tabs(u)));
        }, e -> root.getChildren().setAll(Ui.errorState(com.classsync.service.Errors.userMessage(e), shell::refresh)));
        return root;
    }

    private boolean isProfessor(User u) {
        return u.role() == Role.PROFESSOR && course.professorId() == u.id();
    }

    private Node header(User u) {
        Button back = Ui.button("All courses", Icons.CHEVRON_LEFT, "btn-ghost");
        back.setOnAction(e -> shell.open(Nav.COURSES));
        HBox tags = new HBox(6, Ui.badge(course.code(), "primary"), Ui.badge("Semester " + course.semester(), "neutral"),
                Ui.badge(course.enrolledCount() + " students", "info"));
        if (course.archived()) {
            tags.getChildren().add(Ui.badge("Archived", "warning"));
        }
        VBox text = new VBox(6, tags, Ui.label(course.name(), "h1"),
                Ui.label(course.professorName() + " · " + course.department(), "muted"));
        if (!course.description().isBlank()) {
            text.getChildren().add(Ui.wrapping(course.description(), "muted"));
        }
        HBox actions = new HBox(8);
        if (isProfessor(u)) {
            Button edit = Ui.button("Edit", Icons.NOTE);
            edit.setOnAction(e -> Forms.course(ctx, shell, course, shell::refresh));
            Button archive = Ui.button(course.archived() ? "Restore" : "Archive", Icons.BOOKMARK, "btn-ghost");
            archive.setOnAction(e -> {
                if (course.archived() || Dialogs.confirm(shell.window(), "Archive course",
                        "Archived courses are hidden from everyone's lists but all academic records are kept.", "Archive")) {
                    ctx.background().run(() -> {
                        ctx.courses().setArchived(u, courseId, !course.archived());
                        return null;
                    }, r -> shell.refresh(), shell::showError);
                }
            });
            actions.getChildren().addAll(archive, edit);
        }
        HBox header = new HBox(16, text, Ui.spacer(), actions);
        header.getStyleClass().add("hero-content");
        // course banner over the course's campus photo (same photo as its card)
        return new VBox(8, back, Ui.photoBanner(Images.forCourse(course.id()), "banner-overlay", 18, false, header));
    }

    private Node tabs(User u) {
        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getTabs().add(new Tab("Lectures", Ui.tabScroll(lecturesTab(u))));
        tabs.getTabs().add(new Tab("Materials", Ui.tabScroll(materialsTab(u))));
        if (isProfessor(u)) {
            tabs.getTabs().add(new Tab("Students", Ui.tabScroll(studentsTab(u))));
            tabs.getTabs().add(new Tab("Class Representatives", Ui.tabScroll(staffTab(u))));
            tabs.getTabs().add(new Tab("Syllabus topics", Ui.tabScroll(topicsTab(u))));
        }
        return tabs;
    }

    // ---------------------------------------------------------------- lectures

    private Node lecturesTab(User u) {
        VBox list = new VBox(8, Ui.loading("Loading lectures…"));
        HBox bar = new HBox(8);
        if (perms.contains(CoursePermission.UPDATE_LECTURES) && !course.archived()) {
            Button add = Ui.primary("New lecture", Icons.PLUS);
            add.setOnAction(e -> Forms.lecture(ctx, shell, courseId, null, topics, shell::refresh));
            bar.getChildren().add(add);
        }
        load(() -> ctx.lectures().forCourse(u, courseId), lectures -> {
            list.getChildren().clear();
            if (lectures.isEmpty()) {
                list.getChildren().add(Ui.emptyState(Icons.CALENDAR, "No lectures yet",
                        perms.contains(CoursePermission.UPDATE_LECTURES) ? "Add the first lecture of this course." : "Lectures appear here once published.", null));
            }
            for (Lecture l : lectures) {
                list.getChildren().add(Widgets.lectureRow(l, ctx.lectures().zone(),
                        () -> shell.open(new LectureDetailPage(ctx, shell, l.id()))));
            }
        });
        return new VBox(12, bar, list);
    }

    // ---------------------------------------------------------------- materials

    private Node materialsTab(User u) {
        VBox list = new VBox(8, Ui.loading("Loading materials…"));
        HBox bar = new HBox(8);
        if (perms.contains(CoursePermission.UPLOAD_MATERIALS) && !course.archived()) {
            Button upload = Ui.primary("Upload material", Icons.UPLOAD);
            upload.setOnAction(e -> Forms.upload(ctx, shell, List.of(course), courseId, null, id -> shell.refresh()));
            bar.getChildren().add(upload);
        }
        load(() -> ctx.materials().list(u, new MaterialService.Query(courseId, null, null, null, "newest", 200)), items -> {
            list.getChildren().clear();
            if (items.isEmpty()) {
                list.getChildren().add(Ui.emptyState(Icons.FILE, "No materials yet", null, null));
            }
            for (Material m : items) {
                list.getChildren().add(Widgets.materialRow(ctx, shell, m));
            }
        });
        return new VBox(12, bar, list);
    }

    // ---------------------------------------------------------------- students (professor)

    private Node studentsTab(User u) {
        TableView<EnrolledStudent> table = new TableView<>();
        table.setPlaceholder(Ui.label("No students enrolled yet", "muted"));
        table.getColumns().add(column("Name", s -> s.fullName(), 220));
        table.getColumns().add(column("Enrollment no.", EnrolledStudent::enrollmentNo, 140));
        table.getColumns().add(column("Email", EnrolledStudent::email, 240));
        table.getColumns().add(column("Role", s -> s.role().label(), 160));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPrefHeight(380);

        TextField enrollment = new TextField();
        enrollment.setPromptText("Enrollment number, e.g. CS2024001");
        enrollment.setPrefColumnCount(22);
        Button add = Ui.primary("Enroll", Icons.PLUS);
        Runnable reload = () -> load(() -> ctx.courses().students(u, courseId), table.getItems()::setAll);
        Runnable enroll = () -> ctx.background().run(() -> ctx.courses().enroll(u, courseId, enrollment.getText()), s -> {
            shell.toast().success(s.fullName() + " enrolled.");
            enrollment.clear();
            reload.run();
        }, shell::showError);
        add.setOnAction(e -> enroll.run());
        enrollment.setOnAction(e -> enroll.run());
        Button remove = Ui.button("Remove selected", Icons.X, "btn-ghost");
        remove.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        remove.setOnAction(e -> {
            EnrolledStudent s = table.getSelectionModel().getSelectedItem();
            if (Dialogs.confirm(shell.window(), "Remove student", "Remove " + s.fullName()
                    + " from " + course.code() + "? Their past attendance and scores are kept.", "Remove")) {
                ctx.background().run(() -> {
                    ctx.courses().unenroll(u, courseId, s.userId());
                    return null;
                }, r -> reload.run(), shell::showError);
            }
        });
        reload.run();
        return new VBox(12, Ui.row(8, enrollment, add, Ui.spacer(), remove), table);
    }

    // ---------------------------------------------------------------- CR delegation (professor)

    private Node staffTab(User u) {
        VBox box = new VBox(12);
        Label intro = Ui.wrapping("Class Representatives keep their student features. Being listed here lets them view the "
                + "course schedule (including drafts) and upload materials. Tick extra permissions to delegate more. "
                + "Changes apply on their next action.", "muted");
        ComboBox<EnrolledStudent> candidates = new ComboBox<>();
        candidates.setPromptText("Enrolled CR account");
        candidates.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(EnrolledStudent s) { return s == null ? "" : s.fullName() + " (" + s.enrollmentNo() + ")"; }
            @Override public EnrolledStudent fromString(String s) { return null; }
        });
        Button addCr = Ui.primary("Add as CR staff", Icons.PLUS);
        VBox rows = new VBox(10);
        Runnable[] reload = new Runnable[1];
        reload[0] = () -> {
            record Data(List<StaffMember> staff, List<EnrolledStudent> students) { }
            load(() -> new Data(ctx.courses().staff(u, courseId), ctx.courses().students(u, courseId)), d -> {
                Set<Long> already = new java.util.HashSet<>();
                d.staff().forEach(s -> already.add(s.userId()));
                candidates.getItems().setAll(d.students().stream()
                        .filter(s -> s.role() == Role.CR && !already.contains(s.userId())).toList());
                rows.getChildren().clear();
                if (d.staff().isEmpty()) {
                    rows.getChildren().add(Ui.emptyState(Icons.USERS, "No CR staff",
                            "Only enrolled students with a Class Representative account can be added.", null));
                }
                for (StaffMember s : d.staff()) {
                    rows.getChildren().add(staffRow(u, s, reload[0]));
                }
            });
        };
        addCr.setOnAction(e -> {
            EnrolledStudent s = candidates.getValue();
            if (s == null) {
                shell.toast().error("Choose an enrolled CR first.");
                return;
            }
            ctx.background().run(() -> {
                ctx.courses().setStaffPermissions(u, courseId, s.userId(), Set.of());
                return null;
            }, r -> {
                shell.toast().success(s.fullName() + " added as CR staff.");
                reload[0].run();
            }, shell::showError);
        });
        reload[0].run();
        box.getChildren().addAll(intro, Ui.row(8, candidates, addCr), rows);
        return box;
    }

    private Node staffRow(User u, StaffMember s, Runnable reload) {
        FlowPane checks = new FlowPane(16, 8);
        List<CheckBox> boxes = new java.util.ArrayList<>();
        for (CoursePermission p : CoursePermission.values()) {
            if (!p.delegable()) {
                continue;
            }
            CheckBox cb = new CheckBox(p.label());
            cb.setSelected(s.permissions().contains(p));
            cb.setUserData(p);
            boxes.add(cb);
            checks.getChildren().add(cb);
        }
        Button save = Ui.primary("Save permissions", Icons.CHECK);
        save.setOnAction(e -> {
            Set<CoursePermission> chosen = EnumSet.noneOf(CoursePermission.class);
            boxes.stream().filter(CheckBox::isSelected).forEach(cb -> chosen.add((CoursePermission) cb.getUserData()));
            ctx.background().run(() -> {
                ctx.courses().setStaffPermissions(u, courseId, s.userId(), chosen);
                return null;
            }, r -> shell.toast().success("Permissions updated for " + s.fullName() + "."), shell::showError);
        });
        Button revoke = Ui.button("Remove", Icons.X, "btn-ghost");
        revoke.setOnAction(e -> {
            if (Dialogs.confirm(shell.window(), "Remove CR staff", "Remove all staff rights of " + s.fullName()
                    + " in " + course.code() + "? They remain enrolled as a student.", "Remove")) {
                ctx.background().run(() -> {
                    ctx.courses().removeStaff(u, courseId, s.userId());
                    return null;
                }, r -> reload.run(), shell::showError);
            }
        });
        VBox card = Ui.card(Ui.row(10, Ui.label(s.fullName(), "h3"), Ui.badge(s.enrollmentNo(), "neutral"), Ui.spacer(), revoke, save),
                Ui.label("Default: view schedule, upload materials", "small", "faint"), checks);
        card.getStyleClass().add("card-flat");
        return card;
    }

    // ---------------------------------------------------------------- topics (professor)

    private Node topicsTab(User u) {
        TableView<Topic> table = new TableView<>();
        table.getColumns().add(column("Unit", t -> String.valueOf(t.unitNo()), 80));
        table.getColumns().add(column("Topic", Topic::name, 400));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(Ui.label("No syllabus topics yet", "muted"));
        table.getItems().setAll(topics);
        table.setPrefHeight(360);
        ComboBox<Integer> unit = Forms.numbers(1, 10, 1);
        TextField name = new TextField();
        name.setPromptText("Topic name");
        name.setPrefColumnCount(26);
        Button add = Ui.primary("Add topic", Icons.PLUS);
        Runnable addTopic = () -> ctx.background().run(() -> {
            ctx.courses().addTopic(u, courseId, unit.getValue(), name.getText());
            return ctx.courses().topics(u, courseId);
        }, list -> {
            topics = list;
            table.getItems().setAll(list);
            name.clear();
        }, shell::showError);
        add.setOnAction(e -> addTopic.run());
        name.setOnAction(e -> addTopic.run());
        return new VBox(12, Ui.wrapping("Topics connect lectures, quiz questions and weak-topic analytics.", "muted"),
                Ui.row(8, unit, name, add), table);
    }

    static <T> TableColumn<T, String> column(String title, java.util.function.Function<T, String> value, double width) {
        TableColumn<T, String> col = new TableColumn<>(title);
        col.setCellValueFactory(cd -> new SimpleStringProperty(value.apply(cd.getValue())));
        col.setPrefWidth(width);
        return col;
    }
}
