package com.classsync.view;

import com.classsync.AppContext;
import com.classsync.model.Course;
import com.classsync.model.User;
import com.classsync.service.PlannerService;
import com.classsync.service.PlannerService.PlanRow;
import com.classsync.service.PlannerService.Status;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Semester planner (professor): syllabus topics in order with planned dates and hours. Status comes from the
 * real lectures that cover each topic, so the plan stays in step with the schedule and student previews.
 */
public class PlannerPage extends Page {

    public PlannerPage(AppContext ctx, AppShell shell) {
        super(ctx, shell);
    }

    @Override
    public Nav nav() {
        return Nav.PLANNER;
    }

    @Override
    public Node build() {
        User u = user();
        ComboBox<Course> course = new ComboBox<>();
        course.setConverter(QuizForms.converter(Course::display));
        VBox body = new VBox(16, Ui.loading("Loading courses…"));
        Button add = Ui.primary("Add topic", Icons.PLUS);
        add.setOnAction(e -> {
            if (course.getValue() != null) {
                addTopic(u, course.getValue());
            }
        });
        course.setOnAction(e -> {
            if (course.getValue() != null) {
                show(u, course.getValue(), body);
            }
        });
        load(() -> ctx.courses().myCourses(u, false), mine -> {
            course.getItems().setAll(mine);
            if (mine.isEmpty()) {
                body.getChildren().setAll(Ui.emptyState(Icons.COURSES, "No courses yet", null, null));
            } else {
                course.setValue(mine.get(0));
            }
        });
        return Ui.page(new VBox(16, Ui.pageHeader("Semester planner",
                "Plan when each syllabus topic is taught. A topic is completed when a completed lecture covers it.", add),
                Ui.row(8, Ui.label("Course", "field-label"), course), body));
    }

    private void show(User u, Course c, VBox body) {
        body.getChildren().setAll(Ui.loading("Loading plan…"));
        load(() -> ctx.planner().plan(u, c.id()), plan -> {
            var completion = plan.completion();
            VBox progress = Ui.card("Syllabus completion",
                    Ui.row(12, Ui.label(completion.isPresent() ? Ui.percent(completion.getAsDouble()) : "—", "h2"),
                            Ui.label(plan.completed() + " of " + QuizWidgets.plural(plan.rows().size(), "topic") + " completed", "muted")),
                    Ui.progress(completion.orElse(0), null));
            VBox list = new VBox(8);
            if (plan.rows().isEmpty()) {
                list.getChildren().add(Ui.emptyState(Icons.PLANNER, "No syllabus topics yet", "Add the topics of your syllabus to plan them.", null));
            }
            int unit = -1;
            for (PlanRow r : plan.rows()) {
                if (r.topic().unitNo() != unit) {
                    unit = r.topic().unitNo();
                    list.getChildren().add(Ui.label("UNIT " + unit, "caption"));
                }
                list.getChildren().add(row(u, c, r, body));
            }
            body.getChildren().setAll(progress, Ui.card("Topics", list));
        });
    }

    static Label statusBadge(Status s) {
        return Ui.badge(s.label(), switch (s) {
            case COMPLETED -> "success";
            case SCHEDULED -> "primary";
            case OVERDUE -> "danger";
            case PLANNED -> "info";
            case UNPLANNED -> "neutral";
        });
    }

    private Node row(User u, Course c, PlanRow r, VBox body) {
        var zone = ctx.lectures().zone();
        String lecture = r.completedBy() != null
                ? "Covered in L" + r.completedBy().lectureNo() + " on " + r.completedBy().startsAt().atZone(zone).format(Ui.DATE)
                : r.nextLecture() != null
                ? "L" + r.nextLecture().lectureNo() + " on " + r.nextLecture().startsAt().atZone(zone).format(Ui.DATE)
                : "No lecture linked yet";
        VBox text = new VBox(2, Ui.label(r.topic().name(), "body-strong"),
                Ui.label((r.plannedDate() == null ? "No planned date" : "Planned " + r.plannedDate().format(Ui.DATE))
                        + " · " + QuizWidgets.plural(r.plannedHours(), "hour") + " · " + lecture, "small", "muted"));
        if (!r.notes().isBlank()) {
            text.getChildren().add(Ui.wrapping(r.notes(), "small", "faint"));
        }
        HBox.setHgrow(text, Priority.ALWAYS);
        text.setMinWidth(0);
        Button up = Ui.iconButton(Icons.CHEVRON_LEFT, "Move up");
        up.setRotate(90);
        up.setOnAction(e -> move(u, c, r, true, body));
        Button down = Ui.iconButton(Icons.CHEVRON_RIGHT, "Move down");
        down.setRotate(90);
        down.setOnAction(e -> move(u, c, r, false, body));
        Button edit = Ui.button("Plan", Icons.CALENDAR, "btn-ghost");
        edit.setOnAction(e -> edit(u, c, r, body));
        HBox row = Ui.row(10, text, statusBadge(r.status()), up, down, edit);
        row.getStyleClass().add("list-row");
        return row;
    }

    private void move(User u, Course c, PlanRow r, boolean upwards, VBox body) {
        ctx.background().run(() -> {
            ctx.planner().move(u, r.topic().id(), upwards);
            return null;
        }, x -> show(u, c, body), shell::showError);
    }

    private void edit(User u, Course c, PlanRow r, VBox body) {
        DatePicker date = new DatePicker(r.plannedDate());
        ComboBox<Integer> hours = Forms.numbers(1, 20, r.plannedHours());
        ComboBox<Integer> unit = Forms.numbers(1, 10, r.topic().unitNo());
        TextArea notes = Forms.area(r.notes(), "e.g. use the invoice example; lab follows", 2);
        GridPane grid = Forms.grid();
        grid.add(Ui.formRow("Planned date", date, "Leave empty if not decided yet"), 0, 0);
        grid.add(Ui.formRow("Hours", hours, null), 1, 0);
        grid.add(Ui.formRow("Unit", unit, null), 0, 1);
        grid.add(Ui.formRow("Notes", notes, null), 0, 2, 2, 1);
        if (!Dialogs.form(shell.window(), "Plan · " + r.topic().name(), grid, "Save plan", () -> null)) {
            return;
        }
        ctx.background().run(() -> {
            ctx.planner().setPlan(u, r.topic().id(), date.getValue(), hours.getValue(), unit.getValue(), notes.getText());
            return null;
        }, x -> {
            shell.toast().success("Plan saved.");
            show(u, c, body);
        }, shell::showError);
    }

    private void addTopic(User u, Course c) {
        TextField name = new TextField();
        name.setPromptText("e.g. Query optimisation");
        ComboBox<Integer> unit = Forms.numbers(1, 10, 1);
        GridPane grid = Forms.grid();
        grid.add(Ui.formRow("Topic", name, null), 0, 0);
        grid.add(Ui.formRow("Unit", unit, null), 1, 0);
        if (!Dialogs.form(shell.window(), "Add syllabus topic · " + c.code(), grid, "Add topic",
                () -> name.getText().trim().length() < 2 ? "Enter a topic name." : null)) {
            return;
        }
        ctx.background().run(() -> ctx.courses().addTopic(u, c.id(), unit.getValue(), name.getText()),
                id -> shell.refresh(), shell::showError);
    }

    /** Used by the dashboard: compact syllabus-completion lines for the professor's courses. */
    static HBox completionRow(Course c, PlannerService.Plan plan) {
        var f = plan.completion();
        var bar = Ui.progress(f.orElse(0), null);
        bar.setPrefWidth(160);
        VBox text = new VBox(2, Ui.label(c.code(), "body-strong"),
                Ui.label(plan.rows().isEmpty() ? "No syllabus topics yet" : plan.completed() + " of " + plan.rows().size() + " topics", "small", "muted"));
        HBox.setHgrow(text, Priority.ALWAYS);
        HBox row = Ui.row(12, text, bar, Ui.label(f.isPresent() ? Ui.percent(f.getAsDouble()) : "—", "body-strong"));
        long overdue = plan.rows().stream().filter(r -> r.status() == Status.OVERDUE).count();
        if (overdue > 0) {
            row.getChildren().add(Ui.badge(overdue + " overdue", "danger"));
        }
        row.getStyleClass().add("list-row");
        return row;
    }
}
