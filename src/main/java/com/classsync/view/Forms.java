package com.classsync.view;

import com.classsync.AppContext;
import com.classsync.model.Course;
import com.classsync.model.Lecture;
import com.classsync.model.MaterialKind;
import com.classsync.model.Topic;
import com.classsync.model.User;
import com.classsync.service.CourseService;
import com.classsync.service.LectureService;
import com.classsync.service.MaterialService;
import javafx.collections.FXCollections;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.File;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Create/edit dialogs for courses, lectures and material uploads. */
public final class Forms {

    private Forms() {
    }

    // ---------------------------------------------------------------- course

    public static void course(AppContext ctx, AppShell shell, Course existing, Runnable onSaved) {
        TextField code = new TextField(existing == null ? "" : existing.code());
        code.setPromptText("CS301");
        code.setDisable(existing != null);
        TextField name = new TextField(existing == null ? "" : existing.name());
        name.setPromptText("Database Management Systems");
        TextField dept = new TextField(existing == null ? "" : existing.department());
        dept.setPromptText("Computer Science");
        ComboBox<Integer> sem = numbers(1, 12, existing == null ? 1 : existing.semester());
        TextArea desc = area(existing == null ? "" : existing.description(), "What the course covers", 3);

        GridPane grid = grid();
        grid.add(Ui.formRow("Course code", code, "Letters, digits and '-' (3–12)"), 0, 0);
        grid.add(Ui.formRow("Semester", sem, null), 1, 0);
        grid.add(Ui.formRow("Course name", name, null), 0, 1, 2, 1);
        grid.add(Ui.formRow("Department", dept, null), 0, 2, 2, 1);
        grid.add(Ui.formRow("Description", desc, null), 0, 3, 2, 1);

        boolean ok = Dialogs.form(shell.window(), existing == null ? "New course" : "Edit course", grid,
                existing == null ? "Create course" : "Save changes",
                () -> code.getText().isBlank() || name.getText().isBlank() || dept.getText().isBlank()
                        ? "Code, name and department are required." : null);
        if (!ok) {
            return;
        }
        User u = ctx.session().requireUser();
        CourseService.CourseForm form = new CourseService.CourseForm(code.getText(), name.getText(), dept.getText(),
                sem.getValue(), desc.getText());
        ctx.background().run(() -> {
            if (existing == null) {
                ctx.courses().create(u, form);
            } else {
                ctx.courses().update(u, existing.id(), form);
            }
            return null;
        }, r -> {
            shell.toast().success(existing == null ? "Course created." : "Course updated.");
            onSaved.run();
        }, shell::showError);
    }

    // ---------------------------------------------------------------- lecture

    public static void lecture(AppContext ctx, AppShell shell, long courseId, Lecture existing, List<Topic> topics,
                               Runnable onSaved) {
        var zone = ctx.lectures().zone();
        ZonedDateTime start = existing == null ? null : existing.startsAt().atZone(zone);
        ZonedDateTime end = existing == null ? null : existing.endsAt().atZone(zone);

        TextField topic = new TextField(existing == null ? "" : existing.topic());
        topic.setPromptText("e.g. Normalization: 1NF to BCNF");
        ComboBox<Integer> unit = numbers(1, 10, existing == null ? 1 : existing.unitNo());
        ComboBox<Topic> syllabus = new ComboBox<>(FXCollections.observableArrayList(topics));
        syllabus.setPromptText(topics.isEmpty() ? "No syllabus topics yet" : "Optional");
        if (existing != null && existing.topicId() != null) {
            topics.stream().filter(t -> t.id() == existing.topicId()).findFirst().ifPresent(syllabus::setValue);
        }
        syllabus.valueProperty().addListener((o, a, t) -> {
            if (t != null) {
                unit.setValue(t.unitNo());
                if (topic.getText().isBlank()) {
                    topic.setText(t.name());
                }
            }
        });
        DatePicker date = new DatePicker(start == null ? LocalDate.now(ctx.clock()).plusDays(1) : start.toLocalDate());
        ComboBox<LocalTime> from = times(start == null ? LocalTime.of(10, 0) : start.toLocalTime());
        ComboBox<LocalTime> to = times(end == null ? LocalTime.of(11, 0) : end.toLocalTime());
        TextField room = new TextField(existing == null ? "" : existing.room());
        room.setPromptText("Room / lab");
        TextArea description = area(existing == null ? "" : existing.description(), "What this lecture covers", 3);
        TextArea objectives = area(existing == null ? "" : existing.objectives(), "One objective per line", 3);
        TextArea preparation = area(existing == null ? "" : existing.preparation(), "What students should read or do before class", 3);

        GridPane grid = grid();
        grid.add(Ui.formRow("Topic", topic, null), 0, 0, 2, 1);
        grid.add(Ui.formRow("Syllabus topic", syllabus, null), 0, 1);
        grid.add(Ui.formRow("Unit", unit, null), 1, 1);
        grid.add(Ui.formRow("Date", date, null), 0, 2);
        grid.add(Ui.formRow("Room", room, null), 1, 2);
        grid.add(Ui.formRow("Starts", from, null), 0, 3);
        grid.add(Ui.formRow("Ends", to, null), 1, 3);
        grid.add(Ui.formRow("Description", description, null), 0, 4, 2, 1);
        grid.add(Ui.formRow("Learning objectives", objectives, null), 0, 5, 2, 1);
        grid.add(Ui.formRow("Preparation instructions", preparation, "Shown to students in the upcoming-lecture preview"), 0, 6, 2, 1);

        javafx.scene.control.ScrollPane scroll = new javafx.scene.control.ScrollPane(grid);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(520);
        boolean ok = Dialogs.form(shell.window(), existing == null ? "New lecture" : "Edit lecture " + existing.lectureNo(),
                scroll, existing == null ? "Create lecture" : "Save changes", () -> {
                    if (topic.getText().isBlank()) {
                        return "Enter a topic.";
                    }
                    if (date.getValue() == null || from.getValue() == null || to.getValue() == null) {
                        return "Choose a date, start and end time.";
                    }
                    if (!to.getValue().isAfter(from.getValue())) {
                        return "The end time must be after the start time.";
                    }
                    return null;
                });
        if (!ok) {
            return;
        }
        User u = ctx.session().requireUser();
        LectureService.LectureForm form = new LectureService.LectureForm(courseId,
                existing == null ? null : existing.lectureNo(), unit.getValue(),
                syllabus.getValue() == null ? null : syllabus.getValue().id(), topic.getText(), description.getText(),
                objectives.getText(), preparation.getText(), room.getText(), date.getValue(), from.getValue(), to.getValue());
        ctx.background().run(() -> {
            if (existing == null) {
                ctx.lectures().create(u, form);
            } else {
                ctx.lectures().update(u, existing.id(), form);
            }
            return null;
        }, r -> {
            shell.toast().success(existing == null ? "Lecture created as a draft. Publish it to show students." : "Lecture updated.");
            onSaved.run();
        }, shell::showError);
    }

    // ---------------------------------------------------------------- upload

    /**
     * Material upload dialog. courses = courses where the user may upload; lectureId pre-selects a lecture.
     */
    public static void upload(AppContext ctx, AppShell shell, List<Course> courses, Long courseId, Long lectureId,
                              Consumer<Long> onUploaded) {
        if (courses.isEmpty()) {
            Dialogs.info(shell.window(), "Upload material", "You do not have upload rights in any course.");
            return;
        }
        User u = ctx.session().requireUser();
        ComboBox<Course> course = new ComboBox<>(FXCollections.observableArrayList(courses));
        course.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(Course c) { return c == null ? "" : c.display(); }
            @Override public Course fromString(String s) { return null; }
        });
        ComboBox<Lecture> lecture = new ComboBox<>();
        lecture.setPromptText("Optional — attach to a lecture");
        lecture.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(Lecture l) { return l == null ? "" : l.title(); }
            @Override public Lecture fromString(String s) { return null; }
        });
        course.valueProperty().addListener((o, a, c) -> {
            lecture.getItems().clear();
            if (c != null) {
                ctx.background().run(() -> ctx.lectures().forCourse(u, c.id()), list -> {
                    lecture.getItems().setAll(list);
                    if (lectureId != null) {
                        list.stream().filter(l -> l.id() == lectureId).findFirst().ifPresent(lecture::setValue);
                    }
                }, shell::showError);
            }
        });
        courses.stream().filter(c -> courseId != null && c.id() == courseId).findFirst()
                .ifPresentOrElse(course::setValue, () -> course.setValue(courses.get(0)));

        ComboBox<MaterialKind> kind = new ComboBox<>(FXCollections.observableArrayList(
                MaterialKind.MATERIAL, MaterialKind.PYQ, MaterialKind.REFERENCE));
        kind.setValue(MaterialKind.MATERIAL);
        TextField title = new TextField();
        title.setPromptText("e.g. Week 3 slides — Normalization");
        TextArea description = area("", "Optional description", 2);
        ComboBox<Integer> unit = numbers(1, 10, 1);
        CheckBox publish = new CheckBox("Publish now (visible to enrolled students)");
        publish.setSelected(true);

        File[] chosen = new File[1];
        Label fileLabel = Ui.label("No file chosen", "muted");
        Button choose = Ui.button("Choose file…", Icons.UPLOAD);
        choose.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.setTitle("Choose a file to upload");
            fc.getExtensionFilters().addAll(
                    new FileChooser.ExtensionFilter("Documents and images", "*.pdf", "*.ppt", "*.pptx", "*.doc", "*.docx", "*.jpg", "*.jpeg", "*.png"),
                    new FileChooser.ExtensionFilter("PDF", "*.pdf"));
            File f = fc.showOpenDialog(shell.window());
            if (f != null) {
                chosen[0] = f;
                fileLabel.setText(f.getName() + "  (" + Math.max(1, f.length() / 1024) + " KB)");
                if (title.getText().isBlank()) {
                    String n = f.getName();
                    title.setText(n.contains(".") ? n.substring(0, n.lastIndexOf('.')) : n);
                }
            }
        });
        HBox fileRow = new HBox(10, choose, fileLabel);
        fileRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        GridPane grid = grid();
        grid.add(Ui.formRow("Course", course, null), 0, 0);
        grid.add(Ui.formRow("Type", kind, null), 1, 0);
        grid.add(Ui.formRow("Lecture", lecture, null), 0, 1);
        grid.add(Ui.formRow("Unit", unit, null), 1, 1);
        grid.add(Ui.formRow("File", fileRow, "PDF, PPT/PPTX, DOC/DOCX, JPG/PNG · max "
                + (ctx.materials().storage().maxBytes() / (1024 * 1024)) + " MB"), 0, 2, 2, 1);
        grid.add(Ui.formRow("Title", title, null), 0, 3, 2, 1);
        grid.add(Ui.formRow("Description", description, null), 0, 4, 2, 1);
        grid.add(publish, 0, 5, 2, 1);
        if (u.role() != com.classsync.model.Role.PROFESSOR) {
            grid.add(Ui.wrapping("If you do not hold the 'organise resources' permission, your upload is saved unpublished until a professor publishes it.",
                    "field-hint"), 0, 6, 2, 1);
        }

        boolean ok = Dialogs.form(shell.window(), "Upload material", grid, "Upload", () -> {
            if (course.getValue() == null) {
                return "Choose a course.";
            }
            if (chosen[0] == null) {
                return "Choose a file to upload.";
            }
            return title.getText().isBlank() ? "Enter a title." : null;
        });
        if (!ok) {
            return;
        }
        MaterialService.UploadForm form = new MaterialService.UploadForm(course.getValue().id(),
                lecture.getValue() == null ? null : lecture.getValue().id(), kind.getValue(), title.getText(),
                description.getText(), unit.getValue(), chosen[0].toPath(), publish.isSelected());
        shell.toast().info("Uploading " + chosen[0].getName() + "…");
        ctx.background().run(() -> ctx.materials().upload(u, form), id -> {
            shell.toast().success("Uploaded " + form.title() + ".");
            onUploaded.accept(id);
        }, shell::showError);
    }

    // ---------------------------------------------------------------- helpers

    static GridPane grid() {
        GridPane grid = new GridPane();
        grid.setHgap(14);
        grid.setVgap(12);
        var c1 = new javafx.scene.layout.ColumnConstraints();
        c1.setPercentWidth(60);
        c1.setHgrow(Priority.ALWAYS);
        var c2 = new javafx.scene.layout.ColumnConstraints();
        c2.setPercentWidth(40);
        c2.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(c1, c2);
        return grid;
    }

    static ComboBox<Integer> numbers(int from, int to, int selected) {
        List<Integer> list = new ArrayList<>();
        for (int i = from; i <= to; i++) {
            list.add(i);
        }
        ComboBox<Integer> box = new ComboBox<>(FXCollections.observableArrayList(list));
        box.setValue(selected);
        box.setMaxWidth(Double.MAX_VALUE);
        return box;
    }

    static ComboBox<LocalTime> times(LocalTime selected) {
        List<LocalTime> list = new ArrayList<>();
        for (LocalTime t = LocalTime.of(7, 0); !t.isAfter(LocalTime.of(20, 0)); t = t.plusMinutes(15)) {
            list.add(t);
        }
        if (!list.contains(selected)) {
            list.add(selected);
            list.sort(null);
        }
        ComboBox<LocalTime> box = new ComboBox<>(FXCollections.observableArrayList(list));
        box.setValue(selected);
        box.setMaxWidth(Double.MAX_VALUE);
        box.setVisibleRowCount(10);
        box.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(LocalTime t) { return t == null ? "" : t.format(Ui.TIME); }
            @Override public LocalTime fromString(String s) { return null; }
        });
        return box;
    }

    static TextArea area(String text, String prompt, int rows) {
        TextArea a = new TextArea(text);
        a.setPromptText(prompt);
        a.setWrapText(true);
        a.setPrefRowCount(rows);
        return a;
    }

    static VBox section(String title, javafx.scene.Node... nodes) {
        VBox v = new VBox(8, Ui.label(title, "section-title"));
        v.getChildren().addAll(nodes);
        return v;
    }
}
