package com.classsync.view;

import com.classsync.AppContext;
import com.classsync.model.Course;
import com.classsync.model.Lecture;
import com.classsync.model.LectureStatus;
import com.classsync.model.Material;
import com.classsync.model.Role;
import com.classsync.model.User;
import com.classsync.service.MaterialService;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * Role-specific dashboard. Each section is backed by a real query and is added when its
 * module exists; nothing here shows invented numbers.
 */
public class DashboardPage extends Page {

    /** Everything the dashboard needs, loaded in one background task. */
    record Data(List<Course> courses, List<Course> staffed, List<Lecture> today, List<Lecture> upcoming,
                List<Material> recentMaterials, long newMaterialsThisWeek) {
    }

    public DashboardPage(AppContext ctx, AppShell shell) {
        super(ctx, shell);
    }

    @Override
    public Nav nav() {
        return Nav.DASHBOARD;
    }

    @Override
    public Node build() {
        User u = user();
        VBox body = new VBox(20, hero(u), Ui.loading("Loading your dashboard…"));
        load(() -> {
                    List<Material> latest = ctx.materials().list(u, new MaterialService.Query(null, null, null, null, "newest", 200));
                    Instant weekAgo = ctx.clock().instant().minus(Duration.ofDays(7));
                    long newThisWeek = latest.stream().filter(m -> m.published() && m.createdAt().isAfter(weekAgo)).count();
                    return new Data(
                            ctx.courses().myCourses(u, false),
                            ctx.courses().staffedCourses(u),
                            ctx.lectures().today(u),
                            ctx.lectures().upcoming(u, 8),
                            latest.stream().limit(5).toList(),
                            newThisWeek);
                },
                d -> {
                    body.getChildren().setAll(hero(u), stats(u, d));
                    body.getChildren().addAll(DashboardSections.moduleSections(ctx, shell, u));
                    body.getChildren().add(mainGrid(u, d));
                });
        return Ui.page(body);
    }

    private Node hero(User u) {
        ZonedDateTime now = ZonedDateTime.now(ctx.clock());
        int hour = now.getHour();
        String greeting = hour < 12 ? "Good morning" : hour < 17 ? "Good afternoon" : "Good evening";
        VBox text = new VBox(6,
                Ui.label(now.format(Ui.DATE).toUpperCase(), "caption", "hero-sub"),
                Ui.label(greeting + ", " + u.firstName(), "h1"),
                Ui.wrapping(switch (u.role()) {
                    case STUDENT -> "Here is what is happening in your courses today.";
                    case CR -> "Your courses today, plus the class representative tasks you are trusted with.";
                    case PROFESSOR -> "Your teaching day at a glance.";
                    case ADMIN -> "Account administration.";
                }, "hero-sub"));
        HBox.setHgrow(text, Priority.ALWAYS);
        Button schedule = Ui.button("Open schedule", Icons.CALENDAR);
        schedule.setOnAction(e -> shell.open(Nav.SCHEDULE));
        HBox content = new HBox(16, text, schedule);
        content.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        content.getStyleClass().add("hero-content");
        // campus courtyard photo under a maroon gradient (text stays legible on the left)
        return Ui.photoBanner(Images.CAMPUS_COURTYARD, "hero-overlay", 20, false, content);
    }

    private Node stats(User u, Data d) {
        Instant weekAhead = ctx.clock().instant().plus(Duration.ofDays(7));
        long thisWeek = d.upcoming().stream().filter(l -> l.startsAt().isBefore(weekAhead)
                && l.status() != LectureStatus.CANCELLED).count();
        long todayCount = d.today().stream().filter(l -> l.status() != LectureStatus.CANCELLED).count();
        HBox row = new HBox(16);
        if (u.role() == Role.PROFESSOR) {
            int students = d.courses().stream().mapToInt(Course::enrolledCount).sum();
            row.getChildren().addAll(
                    click(Ui.statCard(Icons.COURSES, String.valueOf(d.courses().size()), "Assigned courses", null, null), Nav.COURSES),
                    Ui.statCard(Icons.USERS, String.valueOf(students), "Enrolled students", "Across all your courses", "stat-teal"),
                    click(Ui.statCard(Icons.CLOCK, String.valueOf(todayCount), "Lectures today", null, "stat-violet"), Nav.SCHEDULE),
                    click(Ui.statCard(Icons.CALENDAR, String.valueOf(thisWeek), "Upcoming in 7 days", null, "stat-amber"), Nav.SCHEDULE));
        } else {
            row.getChildren().addAll(
                    click(Ui.statCard(Icons.COURSES, String.valueOf(d.courses().size()), "Enrolled courses", null, null), Nav.COURSES),
                    click(Ui.statCard(Icons.CLOCK, String.valueOf(todayCount), "Lectures today", null, "stat-violet"), Nav.SCHEDULE),
                    click(Ui.statCard(Icons.CALENDAR, String.valueOf(thisWeek), "Upcoming in 7 days", null, "stat-amber"), Nav.SCHEDULE),
                    click(Ui.statCard(Icons.FILE, String.valueOf(d.newMaterialsThisWeek()), "New materials",
                            "Published in the last 7 days", "stat-teal"), Nav.MATERIALS));
        }
        return row;
    }

    private VBox click(VBox card, Nav target) {
        return Ui.clickable(card, () -> shell.open(target));
    }

    private Node mainGrid(User u, Data d) {
        VBox left = new VBox(20, todayCard(d), upcomingCard(u, d));
        VBox right = new VBox(20);
        if (u.role() == Role.CR) {
            right.getChildren().add(crCard(d));
        }
        if (u.role() == Role.PROFESSOR) {
            right.getChildren().add(coursesCard(d));
        }
        right.getChildren().add(materialsCard(d));
        HBox.setHgrow(left, Priority.ALWAYS);
        left.setPrefWidth(620);
        right.setPrefWidth(420);
        right.setMinWidth(320);
        return new HBox(20, left, right);
    }

    private Node todayCard(Data d) {
        VBox list = new VBox(8);
        if (d.today().isEmpty()) {
            list.getChildren().add(Ui.emptyState(Icons.CALENDAR, "No lectures today", "Enjoy the free time — or get ahead on revision.", null));
        }
        for (Lecture l : d.today()) {
            list.getChildren().add(Widgets.lectureRow(l, ctx.lectures().zone(), () -> openLecture(l)));
        }
        return Ui.card("Today's timetable", list);
    }

    /** Signature feature: upcoming-lecture previews with preparation instructions. */
    private Node upcomingCard(User u, Data d) {
        FlowPane previews = new FlowPane(14, 14);
        List<Lecture> upcoming = d.upcoming().stream().filter(l -> l.status() != LectureStatus.CANCELLED).limit(4).toList();
        if (upcoming.isEmpty()) {
            return Ui.card("Upcoming lectures", Ui.emptyState(Icons.BOOK_OPEN, "Nothing scheduled yet",
                    u.role() == Role.PROFESSOR ? "Create and publish lectures from a course page." : "Published lectures appear here with preparation notes.", null));
        }
        for (Lecture l : upcoming) {
            ZonedDateTime start = l.startsAt().atZone(ctx.lectures().zone());
            VBox card = new VBox(8,
                    Ui.row(6, Ui.badge(l.courseCode(), "primary"), Ui.badge("L" + l.lectureNo(), "neutral"),
                            l.status() == LectureStatus.DRAFT ? Ui.badge("Draft", "warning") : Ui.label("")),
                    Ui.wrapping(l.topic(), "h3"),
                    Ui.label(start.format(Ui.DATE_TIME), "small", "muted"),
                    Ui.label("PREPARE", "caption"),
                    Ui.wrapping(l.preparation().isBlank() ? "No preparation needed." : firstLine(l.preparation()), "small"));
            card.getStyleClass().add("list-row");
            card.setPrefWidth(290);
            Ui.clickable(card, () -> openLecture(l));
            previews.getChildren().add(card);
        }
        Button all = Ui.button("See all", Icons.CHEVRON_RIGHT, "btn-ghost");
        all.setOnAction(e -> shell.open(Nav.SCHEDULE));
        return Ui.card(Ui.row(8, Ui.label("Upcoming lectures — get ready", "card-title"), Ui.spacer(), all), previews);
    }

    private Node crCard(Data d) {
        VBox list = new VBox(8);
        if (d.staffed().isEmpty()) {
            list.getChildren().add(Ui.wrapping("You have no staff duties yet. A professor can add you as CR staff for a course.", "muted"));
        } else {
            for (Course c : d.staffed()) {
                Button open = Ui.button(c.display(), Icons.COURSES, "btn-ghost");
                open.setMaxWidth(Double.MAX_VALUE);
                open.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
                open.setOnAction(e -> shell.open(new CourseDetailPage(ctx, shell, c.id())));
                list.getChildren().add(open);
            }
            Button upload = Ui.primary("Upload material", Icons.UPLOAD);
            upload.setOnAction(e -> Forms.upload(ctx, shell, d.staffed(), null, null, id -> shell.refresh()));
            list.getChildren().add(upload);
        }
        return Ui.card("Class representative duties", list);
    }

    private Node coursesCard(Data d) {
        VBox list = new VBox(8);
        if (d.courses().isEmpty()) {
            Button create = Ui.primary("Create a course", Icons.PLUS);
            create.setOnAction(e -> shell.open(Nav.COURSES));
            list.getChildren().add(Ui.emptyState(Icons.COURSES, "No courses yet", null, create));
        }
        for (Course c : d.courses()) {
            HBox row = Ui.row(10, Ui.badge(c.code(), "primary"), Ui.label(c.name()), Ui.spacer(),
                    Ui.label(c.enrolledCount() + " students", "small", "muted"));
            row.getStyleClass().addAll("list-row", "list-row-clickable");
            row.setOnMouseClicked(e -> shell.open(new CourseDetailPage(ctx, shell, c.id())));
            list.getChildren().add(row);
        }
        return Ui.card("Your courses", list);
    }

    private Node materialsCard(Data d) {
        VBox list = new VBox(8);
        if (d.recentMaterials().isEmpty()) {
            list.getChildren().add(Ui.label("No materials yet.", "faint"));
        }
        for (Material m : d.recentMaterials()) {
            VBox text = new VBox(2, Ui.label(m.title(), "body-strong"), Ui.label(m.courseCode() + " · " + m.sizeText(), "small", "muted"));
            HBox.setHgrow(text, Priority.ALWAYS);
            Button dl = Ui.iconButton(Icons.DOWNLOAD, "Download " + m.title());
            dl.setOnAction(e -> Widgets.downloadFile(ctx, shell, m));
            HBox row = new HBox(10, Widgets.fileTypeChip(m.fileType()), text, dl);
            row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
            row.getStyleClass().add("list-row");
            list.getChildren().add(row);
        }
        return Ui.card("Recent materials", list);
    }

    private void openLecture(Lecture l) {
        shell.open(new LectureDetailPage(ctx, shell, l.id()));
    }

    private static String firstLine(String text) {
        String line = text.strip().split("\\R")[0];
        return line.length() > 140 ? line.substring(0, 137) + "…" : line;
    }
}
