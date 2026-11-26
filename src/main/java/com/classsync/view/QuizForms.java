package com.classsync.view;

import com.classsync.AppContext;
import com.classsync.model.Course;
import com.classsync.model.Lecture;
import com.classsync.model.Question;
import com.classsync.model.QuestionKind;
import com.classsync.model.Quiz;
import com.classsync.model.ReviewPolicy;
import com.classsync.model.Topic;
import com.classsync.model.User;
import com.classsync.service.QuizService;
import javafx.collections.FXCollections;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Dialogs for quiz settings and questions (course professor only; the service re-checks everything). */
public final class QuizForms {

    private static final List<Integer> DURATIONS = List.of(5, 10, 15, 20, 25, 30, 45, 60, 90, 120, 180);

    private QuizForms() {
    }

    // ---------------------------------------------------------------- quiz settings

    /**
     * New or edit quiz. courses = courses the professor teaches; courseId/lectureId preselect values.
     * onSaved receives the quiz id.
     */
    public static void quiz(AppContext ctx, AppShell shell, List<Course> courses, Quiz existing, Long courseId,
                            Long lectureId, Consumer<Long> onSaved) {
        if (courses.isEmpty()) {
            Dialogs.info(shell.window(), "New quiz", "You need a course before creating a quiz.");
            return;
        }
        User u = ctx.session().requireUser();
        var zone = ctx.quizzes().zone();

        ComboBox<Course> course = new ComboBox<>(FXCollections.observableArrayList(courses));
        course.setConverter(converter(Course::display));
        course.setDisable(existing != null);
        ComboBox<Lecture> lecture = new ComboBox<>();
        lecture.setPromptText("Optional — link to a lecture");
        lecture.setConverter(converter(Lecture::title));
        Long wantedLecture = existing != null ? existing.lectureId() : lectureId;
        course.valueProperty().addListener((o, a, c) -> {
            lecture.getItems().clear();
            if (c != null) {
                ctx.background().run(() -> ctx.lectures().forCourse(u, c.id()), list -> {
                    lecture.getItems().setAll(list);
                    if (wantedLecture != null) {
                        list.stream().filter(l -> l.id() == wantedLecture).findFirst().ifPresent(lecture::setValue);
                    }
                }, shell::showError);
            }
        });
        long preselect = existing != null ? existing.courseId() : courseId != null ? courseId : courses.get(0).id();
        courses.stream().filter(c -> c.id() == preselect).findFirst().ifPresentOrElse(course::setValue, () -> course.setValue(courses.get(0)));
        Button clearLecture = Ui.iconButton(Icons.X, "Do not link to a lecture");
        clearLecture.setOnAction(e -> lecture.setValue(null));
        HBox.setHgrow(lecture, Priority.ALWAYS);
        lecture.setMaxWidth(Double.MAX_VALUE);

        TextField title = new TextField(existing == null ? "" : existing.title());
        title.setPromptText("e.g. Joins — quick check");
        TextArea instructions = Forms.area(existing == null ? "" : existing.instructions(),
                "Shown before the student starts, e.g. allowed materials", 2);

        ZonedDateTime defaultOpen = ZonedDateTime.now(ctx.clock()).plusHours(1).withMinute(0).withSecond(0).withNano(0);
        ZonedDateTime open = existing == null ? defaultOpen : existing.opensAt().atZone(zone);
        ZonedDateTime close = existing == null ? defaultOpen.plusDays(2) : existing.closesAt().atZone(zone);
        DatePicker openDate = new DatePicker(open.toLocalDate());
        ComboBox<LocalTime> openTime = allTimes(open.toLocalTime());
        DatePicker closeDate = new DatePicker(close.toLocalDate());
        ComboBox<LocalTime> closeTime = allTimes(close.toLocalTime());
        Button openNow = Ui.button("Open now", Icons.CLOCK, "btn-ghost");
        openNow.setOnAction(e -> {
            ZonedDateTime now = ZonedDateTime.now(ctx.clock()).withSecond(0).withNano(0);
            openDate.setValue(now.toLocalDate());
            openTime.getItems().add(now.toLocalTime());
            openTime.getItems().sort(null);
            openTime.setValue(now.toLocalTime());
        });

        ComboBox<Integer> duration = new ComboBox<>(FXCollections.observableArrayList(DURATIONS));
        int currentDuration = existing == null ? 15 : existing.durationMinutes();
        if (!duration.getItems().contains(currentDuration)) {
            duration.getItems().add(currentDuration);
            duration.getItems().sort(null);
        }
        duration.setValue(currentDuration);
        duration.setConverter(converter(m -> m + " minutes"));
        duration.setMaxWidth(Double.MAX_VALUE);
        ComboBox<ReviewPolicy> policy = new ComboBox<>(FXCollections.observableArrayList(ReviewPolicy.values()));
        policy.setValue(existing == null ? ReviewPolicy.AFTER_CLOSE : existing.reviewPolicy());
        policy.setMaxWidth(Double.MAX_VALUE);
        CheckBox practice = new CheckBox("Allow practice re-attempts after results are released");
        practice.setSelected(existing == null || existing.allowPractice());

        GridPane grid = Forms.grid();
        grid.add(Ui.formRow("Course", course, null), 0, 0, 2, 1);
        grid.add(Ui.formRow("Lecture", new HBox(6, lecture, clearLecture), null), 0, 1, 2, 1);
        grid.add(Ui.formRow("Title", title, null), 0, 2, 2, 1);
        grid.add(Ui.formRow("Instructions", instructions, null), 0, 3, 2, 1);
        grid.add(Ui.formRow("Opens on", openDate, null), 0, 4);
        grid.add(Ui.formRow("at", new HBox(6, openTime, openNow), null), 1, 4);
        grid.add(Ui.formRow("Closes on", closeDate, null), 0, 5);
        grid.add(Ui.formRow("at", closeTime, null), 1, 5);
        grid.add(Ui.formRow("Time limit", duration, "A student's timer never runs past the closing time"), 0, 6);
        grid.add(Ui.formRow("Students see answers", policy, null), 1, 6);
        grid.add(practice, 0, 7, 2, 1);
        grid.add(Ui.wrapping("Each student gets one official attempt. Practice attempts never change official marks.",
                "field-hint"), 0, 8, 2, 1);

        ScrollPane scroll = new ScrollPane(grid);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(520);
        boolean ok = Dialogs.form(shell.window(), existing == null ? "New quiz" : "Edit quiz settings", scroll,
                existing == null ? "Create quiz" : "Save changes", () -> {
                    if (course.getValue() == null) {
                        return "Choose a course.";
                    }
                    if (title.getText().isBlank()) {
                        return "Enter a title.";
                    }
                    if (openDate.getValue() == null || closeDate.getValue() == null
                            || openTime.getValue() == null || closeTime.getValue() == null) {
                        return "Choose opening and closing dates and times.";
                    }
                    if (!closeDate.getValue().atTime(closeTime.getValue()).isAfter(openDate.getValue().atTime(openTime.getValue()))) {
                        return "The quiz must close after it opens.";
                    }
                    return null;
                });
        if (!ok) {
            return;
        }
        QuizService.QuizForm form = new QuizService.QuizForm(course.getValue().id(),
                lecture.getValue() == null ? null : lecture.getValue().id(), title.getText(), instructions.getText(),
                openDate.getValue(), openTime.getValue(), closeDate.getValue(), closeTime.getValue(),
                duration.getValue(), policy.getValue(), practice.isSelected());
        ctx.background().run(() -> {
            if (existing == null) {
                return ctx.quizzes().create(u, form);
            }
            ctx.quizzes().update(u, existing.id(), form);
            return existing.id();
        }, id -> {
            shell.toast().success(existing == null ? "Quiz created as a draft. Add questions, then publish." : "Quiz updated.");
            onSaved.accept(id);
        }, shell::showError);
    }

    // ---------------------------------------------------------------- question

    public static void question(AppContext ctx, AppShell shell, Quiz quiz, Question existing, List<Topic> topics,
                                Runnable onSaved) {
        User u = ctx.session().requireUser();
        ComboBox<QuestionKind> kind = new ComboBox<>(FXCollections.observableArrayList(QuestionKind.values()));
        kind.setValue(existing == null ? QuestionKind.MCQ : existing.kind());
        kind.setMaxWidth(Double.MAX_VALUE);
        TextArea prompt = Forms.area(existing == null ? "" : existing.prompt(), "Write the question", 3);
        ComboBox<Integer> marks = Forms.numbers(1, 20, existing == null ? 1 : Math.min(20, existing.marks()));
        ComboBox<Topic> topic = new ComboBox<>(FXCollections.observableArrayList(topics));
        topic.setPromptText(topics.isEmpty() ? "No syllabus topics in this course" : "Optional — used for weak topics");
        topic.setMaxWidth(Double.MAX_VALUE);
        if (existing != null && existing.topicId() != null) {
            topics.stream().filter(t -> t.id() == existing.topicId()).findFirst().ifPresent(topic::setValue);
        }
        TextArea explanation = Forms.area(existing == null ? "" : existing.explanation(),
                "Shown to students in the review after results are released", 2);

        // multiple-choice options: radio button marks the correct one
        ToggleGroup correct = new ToggleGroup();
        List<TextField> optionFields = new ArrayList<>();
        VBox optionsBox = new VBox(8);
        Button addOption = Ui.button("Add option", Icons.PLUS, "btn-ghost");
        Runnable[] addRow = new Runnable[1];
        addRow[0] = () -> {
            TextField field = new TextField();
            field.setPromptText("Option " + (char) ('A' + optionFields.size()));
            RadioButton isCorrect = new RadioButton();
            isCorrect.setToggleGroup(correct);
            isCorrect.setUserData(optionFields.size());
            isCorrect.setAccessibleText("Mark option " + (char) ('A' + optionFields.size()) + " as correct");
            HBox.setHgrow(field, Priority.ALWAYS);
            optionFields.add(field);
            optionsBox.getChildren().add(Ui.row(10, isCorrect, field));
            addOption.setDisable(optionFields.size() >= 6);
        };
        addOption.setOnAction(e -> addRow[0].run());
        int existingCount = existing != null && existing.kind() == QuestionKind.MCQ ? existing.options().size() : 0;
        for (int i = 0; i < Math.max(4, existingCount); i++) {
            addRow[0].run();
        }
        if (existingCount > 0) {
            for (int i = 0; i < existingCount; i++) {
                optionFields.get(i).setText(existing.options().get(i).text());
                if (existing.options().get(i).correct()) {
                    correct.getToggles().get(i).setSelected(true);
                }
            }
        }
        VBox mcqSection = Forms.section("Options — select the correct answer", optionsBox, addOption);

        ToggleGroup tf = new ToggleGroup();
        RadioButton isTrue = new RadioButton("True");
        RadioButton isFalse = new RadioButton("False");
        isTrue.setToggleGroup(tf);
        isFalse.setToggleGroup(tf);
        if (existing != null && existing.kind() == QuestionKind.TRUE_FALSE) {
            (existing.options().get(0).correct() ? isTrue : isFalse).setSelected(true);
        }
        VBox tfSection = Forms.section("Correct answer", Ui.row(24, isTrue, isFalse));

        Runnable showKind = () -> {
            boolean mcq = kind.getValue() == QuestionKind.MCQ;
            mcqSection.setVisible(mcq);
            mcqSection.setManaged(mcq);
            tfSection.setVisible(!mcq);
            tfSection.setManaged(!mcq);
        };
        kind.valueProperty().addListener((o, a, b) -> showKind.run());
        showKind.run();

        GridPane grid = Forms.grid();
        grid.add(Ui.formRow("Type", kind, null), 0, 0);
        grid.add(Ui.formRow("Marks", marks, null), 1, 0);
        grid.add(Ui.formRow("Question", prompt, null), 0, 1, 2, 1);
        grid.add(mcqSection, 0, 2, 2, 1);
        grid.add(tfSection, 0, 3, 2, 1);
        grid.add(Ui.formRow("Syllabus topic", topic, null), 0, 4, 2, 1);
        grid.add(Ui.formRow("Explanation", explanation, null), 0, 5, 2, 1);
        ScrollPane scroll = new ScrollPane(grid);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(520);

        boolean ok = Dialogs.form(shell.window(), existing == null ? "Add question" : "Edit question " + existing.position(),
                scroll, existing == null ? "Add question" : "Save question", () -> {
                    if (prompt.getText().isBlank()) {
                        return "Write the question.";
                    }
                    if (kind.getValue() == QuestionKind.TRUE_FALSE) {
                        return tf.getSelectedToggle() == null ? "Choose whether the statement is true or false." : null;
                    }
                    long filled = optionFields.stream().filter(f -> !f.getText().isBlank()).count();
                    if (filled < 2) {
                        return "Enter at least two options.";
                    }
                    if (correct.getSelectedToggle() == null
                            || optionFields.get((Integer) correct.getSelectedToggle().getUserData()).getText().isBlank()) {
                        return "Select the correct option (it cannot be empty).";
                    }
                    return null;
                });
        if (!ok) {
            return;
        }
        QuizService.QuestionForm form = kind.getValue() == QuestionKind.TRUE_FALSE
                ? new QuizService.QuestionForm(QuestionKind.TRUE_FALSE, prompt.getText(), marks.getValue(),
                        topic.getValue() == null ? null : topic.getValue().id(), explanation.getText(), null,
                        isTrue.isSelected() ? 0 : 1)
                : new QuizService.QuestionForm(QuestionKind.MCQ, prompt.getText(), marks.getValue(),
                        topic.getValue() == null ? null : topic.getValue().id(), explanation.getText(),
                        optionFields.stream().map(TextField::getText).toList(),
                        (Integer) correct.getSelectedToggle().getUserData());
        ctx.background().run(() -> {
            if (existing == null) {
                ctx.quizzes().addQuestion(u, quiz.id(), form);
            } else {
                ctx.quizzes().updateQuestion(u, existing.id(), form);
            }
            return null;
        }, r -> {
            shell.toast().success(existing == null ? "Question added." : "Question saved.");
            onSaved.run();
        }, shell::showError);
    }

    // ---------------------------------------------------------------- helpers

    static <T> StringConverter<T> converter(java.util.function.Function<T, String> text) {
        return new StringConverter<>() {
            @Override public String toString(T t) { return t == null ? "" : text.apply(t); }
            @Override public T fromString(String s) { return null; }
        };
    }

    /** Every quarter hour of the day (quizzes may open in the evening). */
    static ComboBox<LocalTime> allTimes(LocalTime selected) {
        List<LocalTime> list = new ArrayList<>();
        for (int m = 0; m < 24 * 60; m += 15) {
            list.add(LocalTime.of(m / 60, m % 60));
        }
        if (!list.contains(selected)) {
            list.add(selected);
            list.sort(null);
        }
        ComboBox<LocalTime> box = new ComboBox<>(FXCollections.observableArrayList(list));
        box.setValue(selected);
        box.setVisibleRowCount(10);
        box.setMaxWidth(Double.MAX_VALUE);
        box.setConverter(converter(t -> t.format(Ui.TIME)));
        return box;
    }

    static String window(Quiz q, java.time.ZoneId zone) {
        ZonedDateTime o = q.opensAt().atZone(zone);
        ZonedDateTime c = q.closesAt().atZone(zone);
        String close = o.toLocalDate().equals(c.toLocalDate()) ? c.format(Ui.TIME) : c.format(Ui.DATE_TIME);
        return o.format(Ui.DATE_TIME) + " – " + close;
    }

}
