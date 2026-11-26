package com.classsync.view;

import com.classsync.AppContext;
import com.classsync.model.Course;
import com.classsync.model.Role;
import com.classsync.model.User;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Course cards: taught courses for professors, enrolled courses for students and CRs. */
public class CoursesPage extends Page {

    private final StackPane body = new StackPane();

    public CoursesPage(AppContext ctx, AppShell shell) {
        super(ctx, shell);
    }

    @Override
    public Nav nav() {
        return Nav.COURSES;
    }

    @Override
    public Node build() {
        User u = user();
        CheckBox showArchived = new CheckBox("Show archived");
        showArchived.setVisible(u.role() == Role.PROFESSOR);
        showArchived.selectedProperty().addListener((o, a, b) -> reload(b));
        Node[] actions;
        if (u.role() == Role.PROFESSOR) {
            Button create = Ui.primary("New course", Icons.PLUS);
            create.setOnAction(e -> Forms.course(ctx, shell, null, () -> reload(showArchived.isSelected())));
            actions = new Node[] {showArchived, create};
        } else {
            actions = new Node[0];
        }
        String subtitle = u.role() == Role.PROFESSOR ? "Courses assigned to you" : "Courses you are enrolled in";
        reload(false);
        return Ui.page(new VBox(18, Ui.pageHeader("My Courses", subtitle, actions), body));
    }

    private void reload(boolean includeArchived) {
        User u = user();
        body.getChildren().setAll(Ui.loading("Loading courses…"));
        record Data(List<Course> courses, Set<Long> staffed) { }
        load(() -> new Data(ctx.courses().myCourses(u, includeArchived),
                        ctx.courses().staffedCourses(u).stream().map(Course::id).collect(Collectors.toSet())),
                data -> {
                    if (data.courses().isEmpty()) {
                        body.getChildren().setAll(Ui.emptyState(Icons.COURSES, "No courses yet",
                                u.role() == Role.PROFESSOR ? "Create your first course to start adding lectures."
                                        : "Your professor will enroll you using your enrollment number.", null));
                        return;
                    }
                    FlowPane grid = new FlowPane(18, 18);
                    for (Course c : data.courses()) {
                        grid.getChildren().add(card(c, data.staffed().contains(c.id())));
                    }
                    body.getChildren().setAll(grid);
                });
    }

    private Node card(Course c, boolean staffed) {
        HBox tags = new HBox(6, Ui.badge(c.code(), "primary"), Ui.badge("Sem " + c.semester(), "neutral"));
        if (staffed) {
            tags.getChildren().add(Ui.badge("CR staff", "info"));
        }
        if (c.archived()) {
            tags.getChildren().add(Ui.badge("Archived", "warning"));
        }
        // Google Classroom style: a campus-photo banner carrying the course identity, details below
        VBox bannerText = new VBox(6, tags, Ui.wrapping(c.name(), "h3"), Ui.label(c.professorName(), "muted"));
        bannerText.getStyleClass().add("course-banner-content");
        bannerText.setMinHeight(116);
        javafx.scene.layout.StackPane banner = Ui.photoBanner(Images.forCourse(c.id()), "banner-overlay", 16, true, bannerText);
        VBox body = new VBox(8, Ui.row(16, Ui.row(6, Icons.of(Icons.USERS, 16), Ui.label(c.enrolledCount() + " students", "small", "muted")),
                Ui.label(c.department(), "small", "faint")));
        body.getStyleClass().add("course-card-body");
        VBox card = new VBox(banner, body);
        card.getStyleClass().addAll("card", "course-card");
        card.setPrefWidth(300);
        card.setMinHeight(170);
        return Ui.clickable(card, () -> shell.open(new CourseDetailPage(ctx, shell, c.id())));
    }
}
