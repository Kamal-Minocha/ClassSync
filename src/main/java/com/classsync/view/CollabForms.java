package com.classsync.view;

import com.classsync.AppContext;
import com.classsync.dao.FeedbackDao;
import com.classsync.model.AnnouncementCategory;
import com.classsync.model.Course;
import com.classsync.model.Lecture;
import com.classsync.model.Material;
import com.classsync.model.User;
import com.classsync.service.AnnouncementService;
import com.classsync.service.DiscussionService;
import com.classsync.service.FeedbackService;
import com.classsync.service.NoteService;
import javafx.collections.FXCollections;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.stage.FileChooser;

import java.io.File;
import java.util.List;
import java.util.function.Consumer;

/** Dialogs for feedback, notes, doubts and announcements. Every service call re-checks permissions. */
public final class CollabForms {

    private CollabForms() {
    }

    // ---------------------------------------------------------------- feedback

    public static void feedback(AppContext ctx, AppShell shell, Lecture lecture, FeedbackDao.Own existing, Runnable onSaved) {
        User u = ctx.session().requireUser();
        ComboBox<Integer> understanding = rating(existing == null ? null : existing.ratings().understanding(),
                "Not at all", "Completely");
        ComboBox<Integer> clarity = rating(existing == null ? null : existing.ratings().clarity(), "Very unclear", "Very clear");
        ComboBox<Integer> pace = rating(existing == null ? null : existing.ratings().pace(), "Far too fast or slow", "Just right");
        ComboBox<Integer> usefulness = rating(existing == null ? null : existing.ratings().usefulness(), "Not useful", "Very useful");
        TextArea difficult = Forms.area(existing == null ? "" : existing.difficult(), "Which concepts were hard to follow?", 2);
        TextArea suggestions = Forms.area(existing == null ? "" : existing.suggestions(), "What would help next time?", 2);
        GridPane grid = Forms.grid();
        grid.add(Ui.formRow("Understanding", understanding, "How well did you understand the lecture?"), 0, 0);
        grid.add(Ui.formRow("Clarity", clarity, "How clearly was it explained?"), 1, 0);
        grid.add(Ui.formRow("Pace", pace, null), 0, 1);
        grid.add(Ui.formRow("Usefulness", usefulness, null), 1, 1);
        grid.add(Ui.formRow("Difficult concepts", difficult, null), 0, 2, 2, 1);
        grid.add(Ui.formRow("Suggestions", suggestions, null), 0, 3, 2, 1);
        grid.add(Ui.wrapping(lecture.feedbackAnonymous() || (existing != null && existing.anonymous())
                ? "This feedback is anonymous: your professor sees your ratings and comments without your name. "
                  + "ClassSync stores who submitted only to allow one response per student."
                : "This feedback is NOT anonymous: your professor sees your name with your comments.", "field-hint"), 0, 4, 2, 1);
        boolean ok = Dialogs.form(shell.window(), "Feedback · L" + lecture.lectureNo() + " " + lecture.topic(), grid,
                existing == null ? "Submit feedback" : "Save changes", () ->
                        understanding.getValue() == null || clarity.getValue() == null || pace.getValue() == null
                                || usefulness.getValue() == null ? "Choose a rating for all four questions." : null);
        if (!ok) {
            return;
        }
        var form = new FeedbackService.FeedbackForm(understanding.getValue(), clarity.getValue(), pace.getValue(),
                usefulness.getValue(), difficult.getText(), suggestions.getText());
        ctx.background().run(() -> {
            ctx.feedback().submit(u, lecture.id(), form);
            return null;
        }, r -> {
            shell.toast().success("Thank you — your feedback was saved.");
            onSaved.run();
        }, shell::showError);
    }

    private static ComboBox<Integer> rating(Integer value, String low, String high) {
        ComboBox<Integer> box = new ComboBox<>(FXCollections.observableArrayList(5, 4, 3, 2, 1));
        box.setPromptText("Choose 1–5");
        box.setConverter(QuizForms.converter(i -> i + (i == 5 ? " – " + high : i == 1 ? " – " + low : "")));
        box.setValue(value);
        box.setMaxWidth(Double.MAX_VALUE);
        return box;
    }

    // ---------------------------------------------------------------- notes

    static void submitNote(AppContext ctx, AppShell shell, List<Course> courses, Long courseId, Long lectureId,
                           Runnable onSaved) {
        if (courses.isEmpty()) {
            Dialogs.info(shell.window(), "Submit notes", "You are not enrolled in any course.");
            return;
        }
        User u = ctx.session().requireUser();
        ComboBox<Course> course = new ComboBox<>(FXCollections.observableArrayList(courses));
        course.setConverter(QuizForms.converter(Course::display));
        course.setMaxWidth(Double.MAX_VALUE);
        ComboBox<Lecture> lecture = new ComboBox<>();
        lecture.setPromptText("Optional — which lecture are these notes for?");
        lecture.setConverter(QuizForms.converter(Lecture::title));
        lecture.setMaxWidth(Double.MAX_VALUE);
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
        TextField title = new TextField();
        title.setPromptText("e.g. Normalization — worked examples");
        TextArea description = Forms.area("", "What do these notes cover?", 2);
        File[] chosen = new File[1];
        HBox fileRow = fileChooser(shell, chosen, title);
        GridPane grid = Forms.grid();
        grid.add(Ui.formRow("Course", course, null), 0, 0, 2, 1);
        grid.add(Ui.formRow("Lecture", lecture, null), 0, 1, 2, 1);
        grid.add(Ui.formRow("File", fileRow, "Scanned handwriting (JPG/PNG/PDF) or a document"), 0, 2, 2, 1);
        grid.add(Ui.formRow("Title", title, null), 0, 3, 2, 1);
        grid.add(Ui.formRow("Description", description, null), 0, 4, 2, 1);
        grid.add(Ui.wrapping("Notes are checked by your professor or a class representative before classmates can see them.",
                "field-hint"), 0, 5, 2, 1);
        boolean ok = Dialogs.form(shell.window(), "Submit notes", grid, "Submit for review", () ->
                chosen[0] == null ? "Choose a file." : title.getText().isBlank() ? "Enter a title." : null);
        if (!ok) {
            return;
        }
        var form = new NoteService.NoteForm(course.getValue().id(), lecture.getValue() == null ? null : lecture.getValue().id(),
                title.getText(), description.getText(), lecture.getValue() == null ? null : lecture.getValue().unitNo(),
                chosen[0].toPath());
        ctx.background().run(() -> ctx.notes().submit(u, form), id -> {
            shell.toast().success("Submitted for review.");
            onSaved.run();
        }, shell::showError);
    }

    static void resubmitNote(AppContext ctx, AppShell shell, Material note, String lastComment, Runnable onSaved) {
        User u = ctx.session().requireUser();
        TextField title = new TextField(note.title());
        TextArea description = Forms.area(note.description(), "What do these notes cover?", 2);
        File[] chosen = new File[1];
        HBox fileRow = fileChooser(shell, chosen, null);
        GridPane grid = Forms.grid();
        if (lastComment != null && !lastComment.isBlank()) {
            grid.add(Ui.wrapping("Reviewer: “" + lastComment + "”", "muted"), 0, 0, 2, 1);
        }
        grid.add(Ui.formRow("Replace file (optional)", fileRow, "Current file: " + note.originalFilename()), 0, 1, 2, 1);
        grid.add(Ui.formRow("Title", title, null), 0, 2, 2, 1);
        grid.add(Ui.formRow("Description", description, null), 0, 3, 2, 1);
        boolean ok = Dialogs.form(shell.window(), "Resubmit notes", grid, "Resubmit for review",
                () -> title.getText().isBlank() ? "Enter a title." : null);
        if (!ok) {
            return;
        }
        ctx.background().run(() -> {
            ctx.notes().resubmit(u, note.id(), title.getText(), description.getText(), chosen[0] == null ? null : chosen[0].toPath());
            return null;
        }, r -> {
            shell.toast().success("Resubmitted. It is back in the review queue.");
            onSaved.run();
        }, shell::showError);
    }

    private static HBox fileChooser(AppShell shell, File[] chosen, TextField titleToFill) {
        Label name = Ui.label("No file chosen", "muted");
        Button choose = Ui.button("Choose file…", Icons.UPLOAD);
        choose.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.setTitle("Choose your notes");
            fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Notes (PDF, images, documents)",
                    "*.pdf", "*.jpg", "*.jpeg", "*.png", "*.doc", "*.docx", "*.ppt", "*.pptx"));
            File f = fc.showOpenDialog(shell.window());
            if (f != null) {
                chosen[0] = f;
                name.setText(f.getName() + "  (" + Math.max(1, f.length() / 1024) + " KB)");
                if (titleToFill != null && titleToFill.getText().isBlank()) {
                    String n = f.getName();
                    titleToFill.setText(n.contains(".") ? n.substring(0, n.lastIndexOf('.')) : n);
                }
            }
        });
        return Ui.row(10, choose, name);
    }

    // ---------------------------------------------------------------- doubts

    public static void askDoubt(AppContext ctx, AppShell shell, List<Course> courses, Long courseId, Long lectureId,
                         Consumer<Long> onSaved) {
        if (courses.isEmpty()) {
            Dialogs.info(shell.window(), "Ask a doubt", "You are not a member of any course.");
            return;
        }
        User u = ctx.session().requireUser();
        ComboBox<Course> course = new ComboBox<>(FXCollections.observableArrayList(courses));
        course.setConverter(QuizForms.converter(Course::display));
        course.setMaxWidth(Double.MAX_VALUE);
        ComboBox<Lecture> lecture = new ComboBox<>();
        lecture.setPromptText("Optional — link it to a lecture (sets the topic)");
        lecture.setConverter(QuizForms.converter(Lecture::title));
        lecture.setMaxWidth(Double.MAX_VALUE);
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
        TextField title = new TextField();
        title.setPromptText("Your question in one line");
        TextArea body = Forms.area("", "Add details: what you tried, where you got stuck", 5);
        GridPane grid = Forms.grid();
        grid.add(Ui.formRow("Course", course, null), 0, 0, 2, 1);
        grid.add(Ui.formRow("Lecture", lecture, null), 0, 1, 2, 1);
        grid.add(Ui.formRow("Question", title, null), 0, 2, 2, 1);
        grid.add(Ui.formRow("Details", body, null), 0, 3, 2, 1);
        boolean ok = Dialogs.form(shell.window(), "Ask a doubt", grid, "Post doubt",
                () -> title.getText().trim().length() < 5 ? "Write your question (at least 5 characters)." : null);
        if (!ok) {
            return;
        }
        var form = new DiscussionService.DoubtForm(course.getValue().id(),
                lecture.getValue() == null ? null : lecture.getValue().id(), title.getText(), body.getText());
        ctx.background().run(() -> ctx.discussions().ask(u, form), onSaved, shell::showError);
    }

    // ---------------------------------------------------------------- announcements

    public static void announcement(AppContext ctx, AppShell shell, List<Course> courses, Runnable onSaved) {
        if (courses.isEmpty()) {
            Dialogs.info(shell.window(), "New announcement", "You do not have permission to publish announcements in any course.");
            return;
        }
        User u = ctx.session().requireUser();
        ComboBox<Course> course = new ComboBox<>(FXCollections.observableArrayList(courses));
        course.setConverter(QuizForms.converter(Course::display));
        course.setValue(courses.get(0));
        course.setMaxWidth(Double.MAX_VALUE);
        ComboBox<AnnouncementCategory> category = new ComboBox<>(FXCollections.observableArrayList(AnnouncementCategory.values()));
        category.setValue(AnnouncementCategory.GENERAL);
        category.setMaxWidth(Double.MAX_VALUE);
        TextField title = new TextField();
        title.setPromptText("e.g. Tomorrow's lecture moves to LH-2");
        TextArea body = Forms.area("", "Message", 5);
        GridPane grid = Forms.grid();
        grid.add(Ui.formRow("Course", course, "Everyone enrolled in this course is notified"), 0, 0);
        grid.add(Ui.formRow("Category", category, null), 1, 0);
        grid.add(Ui.formRow("Title", title, null), 0, 1, 2, 1);
        grid.add(Ui.formRow("Message", body, null), 0, 2, 2, 1);
        boolean ok = Dialogs.form(shell.window(), "New announcement", grid, "Publish",
                () -> title.getText().trim().length() < 3 ? "Enter a title." : null);
        if (!ok) {
            return;
        }
        var form = new AnnouncementService.AnnouncementForm(course.getValue().id(), null, category.getValue(),
                title.getText(), body.getText());
        ctx.background().run(() -> ctx.announcements().publish(u, form), id -> {
            shell.toast().success("Announcement published to " + course.getValue().code() + ".");
            onSaved.run();
        }, shell::showError);
    }
}
