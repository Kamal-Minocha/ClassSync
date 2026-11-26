package com.classsync.view;

import com.classsync.AppContext;
import com.classsync.model.CoursePermission;
import com.classsync.model.Lecture;
import com.classsync.model.Quiz;
import com.classsync.model.Role;
import com.classsync.model.User;
import com.classsync.service.QuizService.StudentQuiz;
import javafx.scene.control.Button;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Set;

/**
 * Extra lecture tabs contributed by later modules (quiz, attendance, feedback, notes, doubts).
 * Kept separate so LectureDetailPage stays small; each module adds its tab here when implemented.
 */
final class LectureTabs {

    private LectureTabs() {
    }

    static void addModuleTabs(AppContext ctx, AppShell shell, User u, Lecture lecture, Set<CoursePermission> perms,
                              TabPane tabs) {
        tabs.getTabs().add(new Tab("Quizzes", Ui.tabScroll(quizzes(ctx, shell, u, lecture))));
        tabs.getTabs().add(new Tab("Attendance", Ui.tabScroll(AttendancePanel.build(ctx, shell, u, lecture))));
        tabs.getTabs().add(new Tab("Feedback", Ui.tabScroll(CollabTabs.feedback(ctx, shell, u, lecture))));
        tabs.getTabs().add(new Tab("Notes", Ui.tabScroll(CollabTabs.notes(ctx, shell, u, lecture))));
        tabs.getTabs().add(new Tab("Doubts", Ui.tabScroll(CollabTabs.doubts(ctx, shell, u, lecture))));
    }

    /** Module 5: the lecture's quizzes. Professors manage them; students and CRs attempt them. */
    private static VBox quizzes(AppContext ctx, AppShell shell, User u, Lecture lecture) {
        VBox list = new VBox(10, Ui.loading("Loading quizzes…"));
        VBox box = new VBox(12);
        if (u.role() == Role.PROFESSOR) {
            Button create = Ui.primary("New quiz for this lecture", Icons.PLUS);
            create.setOnAction(e -> ctx.background().run(() -> ctx.courses().get(u, lecture.courseId()),
                    c -> QuizForms.quiz(ctx, shell, List.of(c), null, c.id(), lecture.id(),
                            id -> shell.open(new QuizEditorPage(ctx, shell, id))), shell::showError));
            box.getChildren().add(create);
            ctx.background().run(() -> ctx.quizzes().list(u, lecture.courseId(), lecture.id()), items -> {
                list.getChildren().clear();
                if (items.isEmpty()) {
                    list.getChildren().add(Ui.emptyState(Icons.QUIZ, "No quiz for this lecture yet", null, null));
                }
                for (Quiz q : items) {
                    list.getChildren().add(QuizWidgets.professorRow(ctx, shell, q));
                }
            }, shell::showError);
        } else {
            ctx.background().run(() -> ctx.quizzes().forStudent(u, lecture.courseId(), lecture.id()), items -> {
                list.getChildren().clear();
                if (items.isEmpty()) {
                    list.getChildren().add(Ui.emptyState(Icons.QUIZ, "No quiz for this lecture",
                            "Quizzes appear here when your professor publishes them.", null));
                }
                for (StudentQuiz sq : items) {
                    list.getChildren().add(QuizWidgets.studentRow(ctx, shell, sq));
                }
            }, shell::showError);
        }
        box.getChildren().add(list);
        return box;
    }
}
