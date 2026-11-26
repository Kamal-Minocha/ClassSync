package com.classsync.tools;

import com.classsync.AppContext;
import com.classsync.TestDb;
import com.classsync.config.AppConfig;
import com.classsync.model.User;
import com.classsync.view.AppShell;
import com.classsync.view.Nav;
import com.classsync.view.Theme;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Launches the real JavaFX application (visible window) against the DISPOSABLE test database
 * filled with demo data, drives it like a user, and saves window snapshots as PNG files.
 * The normal classsync database is never touched.
 *
 *   mvnw test-compile exec:java -Dexec.classpathScope=test -Dexec.mainClass=com.classsync.tools.UiSnapshots -Dexec.args="docs/screenshots"
 */
public final class UiSnapshots {

    private static Scene scene;
    private static Stage stage;
    private static AppShell shell;
    private static AppContext ctx;
    private static Path out;

    public static void main(String[] args) {
        try {
            run(args);
        } catch (Throwable t) {
            System.out.println("  FAILED: " + t);
            t.printStackTrace(System.out);
            System.exit(1);   // otherwise exec:java waits forever for the JavaFX thread
        }
    }

    private static void run(String[] args) throws Exception {
        out = Path.of(args.length > 0 ? args[0] : "docs/screenshots");
        Files.createDirectories(out);
        String only = args.length > 1 ? args[1] : "all";

        var db = TestDb.reset();
        Path uploads = Path.of("target", "snapshot-uploads");
        AppConfig config = TestDb.config().with(AppConfig.UPLOAD_DIR, uploads.toString());
        new DemoData(db, config, 10_000).seed();
        ctx = new AppContext(config, db, Clock.system(config.zone()));

        CountDownLatch started = new CountDownLatch(1);
        Platform.startup(started::countDown);
        started.await();
        fx(() -> {
            stage = new Stage();
            stage.setTitle("ClassSync");
            scene = new Scene(new StackPane(), 1440, 900);
            Theme.apply(scene, false);
            stage.setScene(scene);
            shell = new AppShell(ctx, scene);
            shell.showStartup();
            stage.show();
        });
        pause(2500);
        snap("01-login-light");

        run(only, "student", () -> {
            login("asha.rao@college.edu");
            snap("10-student-dashboard");
            fx(() -> shell.open(Nav.COURSES));
            snap("11-student-courses");
            fx(() -> shell.open(Nav.SCHEDULE));
            snap("12-student-schedule");
            fx(() -> shell.open(Nav.MATERIALS));
            snap("13-student-materials");
            openFirstUpcomingLecture();
            snap("14-student-lecture-preview");
            fx(() -> Theme.apply(scene, true));
            fx(() -> shell.open(Nav.DASHBOARD));
            snap("15-student-dashboard-dark");
            fx(() -> Theme.apply(scene, false));
            logout();
        });
        run(only, "professor", () -> {
            login("meera.iyer@college.edu");
            snap("20-professor-dashboard");
            fx(() -> shell.open(Nav.COURSES));
            snap("21-professor-courses");
            openCourse("CS301");
            snap("22-professor-course-detail");
            logout();
        });
        run(only, "cr", () -> {
            login("ravi.kumar@college.edu");
            snap("30-cr-dashboard");
            fx(() -> shell.open(Nav.MATERIALS));
            snap("31-cr-materials");
            logout();
        });
        run(only, "quiz", () -> {
            login("asha.rao@college.edu");
            fx(() -> shell.open(Nav.QUIZZES));
            snap("40-student-quizzes");
            User asha = ctx.session().requireUser();
            long norm = quizId(asha, "Normalization quiz");
            long attempt = ctx.quizzes().start(asha, norm, com.classsync.model.AttemptMode.OFFICIAL);
            var questions = ctx.quizzes().session(asha, attempt).questions();
            // two answers already saved, as if the student had started a minute ago
            ctx.quizzes().saveAnswer(asha, attempt, questions.get(0).id(), questions.get(0).choices().get(0).id());
            ctx.quizzes().saveAnswer(asha, attempt, questions.get(1).id(), questions.get(1).choices().get(0).id());
            fx(() -> shell.open(new com.classsync.view.QuizAttemptPage(ctx, shell, attempt)));
            snap("41-student-quiz-attempt");
            // drive the real UI: jump to question 3 with the navigator, click option B, and check the
            // autosave reached PostgreSQL
            fx(() -> scene.getRoot().lookupAll(".qnav-button").stream()
                    .filter(n -> n instanceof javafx.scene.control.Button b && b.getText().equals("3"))
                    .findFirst().map(javafx.scene.control.Button.class::cast).orElseThrow().fire());
            pause(400);
            fx(() -> scene.getRoot().lookupAll(".radio-button").stream().map(javafx.scene.control.RadioButton.class::cast)
                    .toList().get(1).fire());
            pause(2000);
            var saved = ctx.quizzes().session(asha, attempt).saved();
            long expected = questions.get(2).choices().get(1).id();
            if (saved.size() != 3 || saved.get(questions.get(2).id()) != expected) {
                throw new IllegalStateException("UI autosave did not persist the click: " + saved);
            }
            System.out.println("  verified: clicking an option in the UI saved it to PostgreSQL (3 answers)");
            ctx.quizzes().submit(asha, attempt);
            fx(() -> shell.open(new com.classsync.view.QuizResultPage(ctx, shell, attempt)));
            snap("42-student-quiz-review");
            logout();

            // countdown expiry: Zoya's attempt gets a 6-second deadline; the open attempt screen must
            // auto-submit by itself and land on the result page
            login("zoya.khan@college.edu");
            User zoya = ctx.session().requireUser();
            long zoyaAttempt = ctx.quizzes().start(zoya, norm, com.classsync.model.AttemptMode.OFFICIAL);
            try (var con = ctx.database().open(); var ps = con.prepareStatement(
                    "UPDATE quiz_attempts SET deadline_at = clock_timestamp() + interval '6 seconds' WHERE id = ?")) {
                ps.setLong(1, zoyaAttempt);
                ps.executeUpdate();
            }
            fx(() -> shell.open(new com.classsync.view.QuizAttemptPage(ctx, shell, zoyaAttempt)));
            pause(10_000);
            var finished = ctx.quizzes().result(zoya, zoyaAttempt).attempt();
            if (!finished.status().finished()) {
                throw new IllegalStateException("Countdown did not auto-submit the attempt");
            }
            System.out.println("  verified: countdown reached zero and the attempt was auto-submitted (" + finished.status() + ")");
            logout();

            login("meera.iyer@college.edu");
            User meera = ctx.session().requireUser();
            fx(() -> shell.open(Nav.QUIZZES));
            snap("43-professor-quizzes");
            long joins = quizId(meera, "SQL and joins check");
            fx(() -> shell.open(new com.classsync.view.QuizEditorPage(ctx, shell, joins)));
            snap("44-professor-quiz-results");
            long draft = quizId(meera, "Concurrency control (draft)");
            fx(() -> shell.open(new com.classsync.view.QuizEditorPage(ctx, shell, draft)));
            snap("45-professor-quiz-editor");
            logout();
        });
        run(only, "collab", () -> {
            login("asha.rao@college.edu");
            User asha = ctx.session().requireUser();
            pause(1000);
            int unread = ctx.notifications().unreadCount(asha);
            AtomicReference<String> badge = new AtomicReference<>();
            fx(() -> badge.set(((javafx.scene.control.Label) scene.getRoot().lookup(".counter-dot")).getText()));
            if (unread == 0 || !badge.get().equals(String.valueOf(unread))) {
                throw new IllegalStateException("Bell shows '" + badge.get() + "' but the database has " + unread + " unread");
            }
            System.out.println("  verified: the top-bar bell shows the unread count from PostgreSQL (" + unread + ")");
            snap("60-student-dashboard-collab");
            fx(() -> shell.open(Nav.NOTES));
            snap("61-student-notes");
            selectTab("My submissions");
            snap("62-student-my-notes");
            fx(() -> shell.open(Nav.DISCUSSIONS));
            snap("63-student-discussions-heatmap");
            long doubt = ctx.discussions().list(asha, new com.classsync.service.DiscussionService.Query(null, null,
                    "LEFT JOIN", false, false, 5)).get(0).id();
            fx(() -> shell.open(new com.classsync.view.DiscussionThreadPage(ctx, shell, doubt)));
            snap("64-student-doubt-thread");
            fx(() -> shell.open(new com.classsync.view.NotificationsPage(ctx, shell)));
            snap("65-student-notifications");
            logout();

            login("ravi.kumar@college.edu");
            fx(() -> shell.open(Nav.NOTES));
            pause(1500);
            selectTab("Review queue");
            snap("66-cr-review-queue");
            logout();

            login("meera.iyer@college.edu");
            User meera = ctx.session().requireUser();
            long l3 = ctx.lectures().forCourse(meera, courseId(meera, "CS301")).stream()
                    .filter(l -> l.lectureNo() == 3).findFirst().orElseThrow().id();
            fx(() -> shell.open(new com.classsync.view.LectureDetailPage(ctx, shell, l3)));
            pause(1500);
            selectTab("Feedback");
            snap("67-professor-feedback-anonymous");
            fx(() -> shell.open(Nav.FEEDBACK));
            snap("68-professor-feedback-trend");
            fx(() -> shell.open(Nav.ANNOUNCEMENTS));
            snap("69-professor-announcements");
            fx(() -> shell.open(Nav.DASHBOARD));
            snap("70-professor-dashboard-collab");
            logout();
        });
        run(only, "progress", () -> {
            login("asha.rao@college.edu");
            User asha = ctx.session().requireUser();
            snap("80-student-dashboard-progress");
            fx(() -> shell.open(Nav.REVISION));
            snap("81-student-revision");
            selectTab("Questions to revise");
            snap("82-student-revision-mistakes");
            fx(() -> shell.open(Nav.PERFORMANCE));
            snap("83-student-performance");
            fx(() -> shell.open(Nav.LIBRARY));
            snap("84-student-library");
            // click the first empty bookmark button in the real library and check PostgreSQL
            int before = ctx.study().bookmarks(asha).size();
            fx(() -> scene.getRoot().lookupAll(".btn-icon").stream()
                    .filter(n -> n instanceof javafx.scene.control.Button b && "Bookmark".equals(b.getAccessibleText()))
                    .map(javafx.scene.control.Button.class::cast).findFirst().orElseThrow().fire());
            pause(1500);
            int after = ctx.study().bookmarks(asha).size();
            if (after != before + 1) {
                throw new IllegalStateException("Bookmark click did not persist: " + before + " -> " + after);
            }
            System.out.println("  verified: the library bookmark button saved a bookmark in PostgreSQL (" + before + " -> " + after + ")");
            fx(() -> shell.open(Nav.BOOKMARKS));
            snap("85-student-bookmarks-tasks");
            logout();

            login("meera.iyer@college.edu");
            fx(() -> shell.open(Nav.PLANNER));
            snap("86-professor-planner");
            fx(() -> shell.open(Nav.DASHBOARD));
            snap("87-professor-dashboard-syllabus");
            logout();
        });
        run(only, "attendance", () -> {
            login("meera.iyer@college.edu");
            User meera = ctx.session().requireUser();
            // today's CS301 lecture; move its start 10 minutes into the past so attendance can open whatever
            // time of day the tool runs (test database only)
            long today = ctx.lectures().forCourse(meera, courseId(meera, "CS301")).stream()
                    .filter(l -> l.lectureNo() == 5).findFirst().orElseThrow().id();
            try (var con = ctx.database().open(); var ps = con.prepareStatement(
                    "UPDATE lectures SET starts_at = now() - interval '10 minutes', ends_at = now() + interval '50 minutes' WHERE id = ?")) {
                ps.setLong(1, today);
                ps.executeUpdate();
            }
            fx(() -> shell.open(new com.classsync.view.LectureDetailPage(ctx, shell, today)));
            pause(1500);
            selectTab("Attendance");
            pause(1200);
            clickButton("Start attendance");   // the real button
            pause(1500);
            String code = codeOnScreen();
            var session = ctx.attendance().openSessions(ctx.auth().login("neha.sharma@college.edu", DemoData.PASSWORD.toCharArray()));
            long sessionId = session.get(0).id();
            for (String email : List.of("neha.sharma@college.edu", "kabir.singh@college.edu")) {
                ctx.attendance().submitCode(ctx.auth().login(email, DemoData.PASSWORD.toCharArray()), sessionId, code);
            }
            pause(4500);   // the professor screen polls every 4 seconds
            snap("50-professor-attendance-live");
            long corrected = ctx.lectures().forCourse(meera, courseId(meera, "CS301")).stream()
                    .filter(l -> l.lectureNo() == 2).findFirst().orElseThrow().id();
            fx(() -> shell.open(new com.classsync.view.LectureDetailPage(ctx, shell, corrected)));
            pause(1500);
            selectTab("Attendance");
            snap("51-professor-attendance-finalized");
            fx(() -> shell.open(Nav.ATTENDANCE));
            snap("52-professor-attendance-report");
            logout();

            login("asha.rao@college.edu");
            User asha = ctx.session().requireUser();
            fx(() -> shell.open(Nav.ATTENDANCE));
            snap("53-student-attendance");
            // type the code into the real code box and press the real button
            fx(() -> {
                var field = (javafx.scene.control.TextField) scene.getRoot().lookupAll(".text-field").stream()
                        .filter(n -> n instanceof javafx.scene.control.TextField t
                                && "6-character code".equals(t.getPromptText()))
                        .findFirst().orElseThrow();
                field.setText(code.toLowerCase());
            });
            clickButton("Mark me present");
            pause(2500);
            var mine = ctx.attendance().myLecture(asha, today);
            if (mine.status() != com.classsync.model.AttendanceStatus.PRESENT) {
                throw new IllegalStateException("Typing the code in the UI did not mark Asha present: " + mine);
            }
            System.out.println("  verified: code typed into the student UI marked the student PRESENT in PostgreSQL");
            fx(() -> shell.open(Nav.DASHBOARD));
            snap("54-student-dashboard-attendance");
            logout();
        });
        run(only, "handover", UiSnapshots::handover);
        run(only, "admin", UiSnapshots::adminScreens);
        run(only, "startup", UiSnapshots::startupScreens);
        fx(() -> {
            Theme.apply(scene, true);
            shell.showLogin();
        });
        snap("02-login-dark");
        fx(() -> Theme.apply(scene, false));
        Platform.exit();
        ctx.background().shutdown();
        System.out.println("Screenshots written to " + out.toAbsolutePath());
        System.exit(0);
    }

    // =====================================================================================
    // Phase 10 handover: real dialogs, error screens, keyboard, small window, dark theme
    // =====================================================================================

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new IllegalStateException("FAILED: " + what);
        }
        System.out.println("  verified: " + what);
    }

    /** Runs code on the FX thread WITHOUT waiting (for actions that open a modal dialog). */
    /** True only if the node and every ancestor are visible (what the user actually sees). */
    private static boolean shownOnScreen(javafx.scene.Node n) {
        for (javafx.scene.Node p = n; p != null; p = p.getParent()) {
            if (!p.isVisible()) {
                return false;
            }
        }
        return true;
    }

    private static void later(Runnable r) {
        Platform.runLater(r);
    }

    /** Waits for a modal dialog window to appear and returns its scene. */
    private static Scene awaitDialog() throws Exception {
        for (int i = 0; i < 60; i++) {
            AtomicReference<Scene> found = new AtomicReference<>();
            fx(() -> javafx.stage.Window.getWindows().stream()
                    .filter(w -> w.isShowing() && w != stage && w.getScene() != null
                            && w.getScene().getRoot().lookup(".dialog-pane") != null)
                    .findFirst().ifPresent(w -> found.set(w.getScene())));
            if (found.get() != null) {
                pause(500);
                return found.get();
            }
            pause(100);
        }
        throw new IllegalStateException("No dialog appeared");
    }

    private static void snapDialog(Scene dialog, String name) throws Exception {
        AtomicReference<WritableImage> img = new AtomicReference<>();
        fx(() -> img.set(dialog.snapshot(null)));
        save(img.get(), out.resolve(name + ".png"));
        System.out.println("  saved " + name);
    }

    private static void fill(Scene s, String prompt, String text) throws Exception {
        fx(() -> s.getRoot().lookupAll(".text-input").stream()
                .map(javafx.scene.control.TextInputControl.class::cast)
                .filter(t -> prompt.equals(t.getPromptText())).findFirst()
                .orElseThrow(() -> new IllegalStateException("No field '" + prompt + "'")).setText(text));
    }

    private static void press(Scene s, String text) throws Exception {
        fx(() -> s.getRoot().lookupAll(".button").stream()
                .filter(n -> n instanceof javafx.scene.control.Button b && text.equals(b.getText()) && !b.isDisabled())
                .map(javafx.scene.control.Button.class::cast).findFirst()
                .orElseThrow(() -> new IllegalStateException("No button '" + text + "'")).fire());
    }

    /** Clicks a page button that opens a modal dialog (must not wait on the FX thread). */
    private static void pressOpeningDialog(String text) {
        later(() -> scene.getRoot().lookupAll(".button").stream()
                .filter(n -> n instanceof javafx.scene.control.Button b && text.equals(b.getText()) && b.isVisible() && !b.isDisabled())
                .map(javafx.scene.control.Button.class::cast).findFirst()
                .orElseThrow(() -> new IllegalStateException("No button '" + text + "'")).fire());
    }

    private static void handover() throws Exception {
        var zone = ctx.lectures().zone();

        // ---------------------------------------------------------------- professor dialogs
        login("meera.iyer@college.edu");
        User meera = ctx.session().requireUser();
        long cs301 = courseId(meera, "CS301");
        var cs301Course = ctx.courses().get(meera, cs301);

        // quiz settings dialog: empty title first (validation keeps the dialog open), then a real quiz
        java.util.concurrent.atomic.AtomicLong newQuiz = new java.util.concurrent.atomic.AtomicLong();
        later(() -> com.classsync.view.QuizForms.quiz(ctx, shell, List.of(cs301Course), null, cs301, null, newQuiz::set));
        Scene d = awaitDialog();
        press(d, "Create quiz");
        pause(300);
        AtomicReference<String> error = new AtomicReference<>();
        fx(() -> error.set(((javafx.scene.control.Label) d.getRoot().lookup(".field-error")).getText()));
        check("Enter a title.".equals(error.get()) && d.getWindow().isShowing(), "quiz dialog refuses an empty title and stays open");
        fill(d, "e.g. Joins — quick check", "Handover check quiz");
        snapDialog(d, "91-dialog-new-quiz");
        press(d, "Create quiz");
        pause(2000);
        check(newQuiz.get() > 0 && ctx.quizzes().editor(meera, newQuiz.get()).quiz().title().equals("Handover check quiz"),
                "new-quiz dialog created the quiz in PostgreSQL");

        // question dialog: two options, mark the second correct
        var editor = ctx.quizzes().editor(meera, newQuiz.get());
        var topics = ctx.courses().topics(meera, cs301);
        later(() -> com.classsync.view.QuizForms.question(ctx, shell, editor.quiz(), null, topics, () -> { }));
        Scene q = awaitDialog();
        fill(q, "Write the question", "Which normal form removes transitive dependencies?");
        fill(q, "Option A", "2NF");
        fill(q, "Option B", "3NF");
        fx(() -> q.getRoot().lookupAll(".radio-button").stream().map(javafx.scene.control.RadioButton.class::cast)
                .filter(r -> r.getText() == null || r.getText().isEmpty()).toList().get(1).setSelected(true));
        snapDialog(q, "92-dialog-question");
        press(q, "Add question");
        pause(2000);
        var questions = ctx.quizzes().editor(meera, newQuiz.get()).questions();
        check(questions.size() == 1 && questions.get(0).correctOption().text().equals("3NF"),
                "question dialog stored the question with the selected correct option");

        // announcement dialog
        User ashaUser = ctx.auth().login("asha.rao@college.edu", DemoData.PASSWORD.toCharArray());
        int unreadBefore = ctx.notifications().unreadCount(ashaUser);
        later(() -> com.classsync.view.CollabForms.announcement(ctx, shell, List.of(cs301Course), () -> { }));
        Scene a = awaitDialog();
        fill(a, "e.g. Tomorrow's lecture moves to LH-2", "Handover check announcement");
        press(a, "Publish");
        pause(2000);
        check(ctx.notifications().unreadCount(ashaUser) == unreadBefore + 1, "announcement dialog published and notified a student once");

        // attendance: finalize confirmation (today's lecture, moved to "started" in the test database)
        long l5 = ctx.lectures().forCourse(meera, cs301).stream().filter(l -> l.lectureNo() == 5).findFirst().orElseThrow().id();
        try (var con = ctx.database().open(); var ps = con.prepareStatement(
                "UPDATE lectures SET starts_at = now() - interval '10 minutes', ends_at = now() + interval '50 minutes' WHERE id = ?")) {
            ps.setLong(1, l5);
            ps.executeUpdate();
        }
        fx(() -> shell.open(new com.classsync.view.LectureDetailPage(ctx, shell, l5)));
        pause(1500);
        selectTab("Attendance");
        pause(1200);
        pressOpeningDialog("Finalize attendance");
        Scene fin = awaitDialog();
        snapDialog(fin, "93-dialog-finalize-attendance");
        press(fin, "Finalize");
        pause(2500);
        check(ctx.lectures().get(meera, l5).attendanceFinalized(), "finalize confirmation finalized the lecture's attendance");

        // attendance correction dialog on the finalized lecture
        int correctionsBefore = ctx.attendance().lecture(meera, l5).corrections().size();
        fx(() -> shell.refresh());
        pause(1500);
        selectTab("Attendance");
        pause(1200);
        pressOpeningDialog("Correct");
        Scene corr = awaitDialog();
        fill(corr, "e.g. Medical certificate submitted; was present but code screen not visible", "Handover check: lab session overlap");
        snapDialog(corr, "94-dialog-attendance-correction");
        press(corr, "Save correction");
        pause(2000);
        check(ctx.attendance().lecture(meera, l5).corrections().size() == correctionsBefore + 1,
                "correction dialog stored a reasoned correction");

        // note review: request changes with a reason
        int queueBefore = ctx.notes().reviewQueue(meera).size();
        fx(() -> shell.open(Nav.NOTES));
        pause(1500);
        selectTab("Review queue");
        pause(800);
        pressOpeningDialog("Request changes");
        Scene rej = awaitDialog();
        fx(() -> ((javafx.scene.control.TextArea) rej.getRoot().lookup(".text-area")).setText("Handover check: please add page numbers"));
        press(rej, "Submit");
        pause(2000);
        check(ctx.notes().reviewQueue(meera).size() == queueBefore - 1, "request-changes dialog rejected the note with a reason");

        // planner: reschedule the first topic
        java.time.LocalDate target = java.time.LocalDate.now(zone).plusDays(30);
        fx(() -> shell.open(Nav.PLANNER));
        pause(2000);
        pressOpeningDialog("Plan");
        Scene plan = awaitDialog();
        fx(() -> ((javafx.scene.control.DatePicker) plan.getRoot().lookup(".date-picker")).setValue(target));
        press(plan, "Save plan");
        pause(2000);
        check(ctx.planner().plan(meera, cs301).rows().get(0).plannedDate().equals(target), "planner dialog rescheduled a topic");

        // keyboard shortcut Ctrl+B collapses the sidebar
        fx(() -> scene.getAccelerators().get(new javafx.scene.input.KeyCodeCombination(javafx.scene.input.KeyCode.B,
                javafx.scene.input.KeyCombination.SHORTCUT_DOWN)).run());
        pause(600);
        AtomicReference<Boolean> collapsed = new AtomicReference<>();
        fx(() -> collapsed.set(scene.getRoot().lookup(".sidebar").getStyleClass().contains("sidebar-collapsed")));
        snap("95-sidebar-collapsed");
        check(collapsed.get(), "Ctrl+B collapses the sidebar");
        fx(() -> scene.getAccelerators().get(new javafx.scene.input.KeyCodeCombination(javafx.scene.input.KeyCode.B,
                javafx.scene.input.KeyCombination.SHORTCUT_DOWN)).run());

        // dark theme: professor screens
        fx(() -> Theme.apply(scene, true));
        fx(() -> shell.open(Nav.PLANNER));
        snap("100-dark-professor-planner");
        long joins = quizId(meera, "SQL and joins check");
        fx(() -> shell.open(new com.classsync.view.QuizEditorPage(ctx, shell, joins)));
        snap("101-dark-professor-quiz-results");
        fx(() -> Theme.apply(scene, false));
        logout();

        // ---------------------------------------------------------------- student dialogs
        login("asha.rao@college.edu");
        User asha = ctx.session().requireUser();
        long l4 = ctx.lectures().forCourse(asha, cs301).stream().filter(l -> l.lectureNo() == 4).findFirst().orElseThrow().id();
        var lecture4 = ctx.lectures().get(asha, l4);
        later(() -> com.classsync.view.CollabForms.feedback(ctx, shell, lecture4, null, () -> { }));
        Scene fb = awaitDialog();
        fx(() -> fb.getRoot().lookupAll(".combo-box").forEach(n -> {
            @SuppressWarnings("unchecked") var box = (javafx.scene.control.ComboBox<Integer>) n;
            box.setValue(4);
        }));
        fill(fb, "What would help next time?", "Handover check: more examples");
        snapDialog(fb, "96-dialog-feedback");
        press(fb, "Submit feedback");
        pause(2000);
        check(ctx.feedback().mine(asha, l4).own() != null, "feedback dialog saved the student's feedback");

        var ashaCourse = ctx.courses().get(asha, cs301);
        later(() -> com.classsync.view.CollabForms.askDoubt(ctx, shell, List.of(ashaCourse), cs301, null, id -> { }));
        Scene ask = awaitDialog();
        fill(ask, "Your question in one line", "Handover check: what is a candidate key?");
        press(ask, "Post doubt");
        pause(2000);
        check(!ctx.discussions().list(asha, new com.classsync.service.DiscussionService.Query(cs301, null,
                "candidate key", false, true, 5)).isEmpty(), "ask-doubt dialog posted the doubt");

        // dark theme: student screens
        fx(() -> Theme.apply(scene, true));
        fx(() -> shell.open(Nav.QUIZZES));
        snap("102-dark-student-quizzes");
        fx(() -> shell.open(Nav.PERFORMANCE));
        snap("103-dark-student-performance");
        fx(() -> shell.open(Nav.ATTENDANCE));
        snap("104-dark-student-attendance");
        fx(() -> Theme.apply(scene, false));

        // stale background results are discarded after logout (AUTH-10)
        java.util.concurrent.atomic.AtomicBoolean delivered = new java.util.concurrent.atomic.AtomicBoolean();
        fx(() -> ctx.background().run(() -> {
            Thread.sleep(1500);
            return 1;
        }, r -> delivered.set(true), e -> { }));
        logout();
        pause(2500);
        check(!delivered.get(), "a task started before logout delivers no result after logout");

        // ---------------------------------------------------------------- quiz submit confirmation (Rohan)
        login("rohan.das@college.edu");
        User rohan = ctx.session().requireUser();
        long norm = quizId(rohan, "Normalization quiz");
        long attempt = ctx.quizzes().start(rohan, norm, com.classsync.model.AttemptMode.OFFICIAL);
        fx(() -> shell.open(new com.classsync.view.QuizAttemptPage(ctx, shell, attempt)));
        pause(2000);
        pressOpeningDialog("Submit");
        Scene sub = awaitDialog();
        snapDialog(sub, "97-dialog-submit-quiz");
        press(sub, "Submit");
        pause(2500);
        check(ctx.quizzes().result(rohan, attempt).attempt().status().finished(), "submit confirmation submitted the attempt");
        logout();

        // ---------------------------------------------------------------- login keyboard order (TAB through the form)
        fx(() -> {
            var email = scene.getRoot().lookupAll(".text-field").stream().map(javafx.scene.control.TextField.class::cast)
                    .filter(t -> "name@college.edu or CS2024001".equals(t.getPromptText())).findFirst().orElseThrow();
            email.requestFocus();
        });
        pause(300);
        List<String> order = new java.util.ArrayList<>();
        for (int i = 0; i < 4; i++) {
            fx(() -> javafx.event.Event.fireEvent(scene.getFocusOwner(), new javafx.scene.input.KeyEvent(
                    javafx.scene.input.KeyEvent.KEY_PRESSED, "\t", "\t", javafx.scene.input.KeyCode.TAB, false, false, false, false)));
            pause(200);
            fx(() -> {
                var f = scene.getFocusOwner();
                order.add(f instanceof javafx.scene.control.Labeled l ? f.getClass().getSimpleName() + ":" + l.getText()
                        : f.getClass().getSimpleName());
            });
        }
        System.out.println("  TAB order from the email field: " + order);
        check(order.get(0).startsWith("PasswordField") && order.stream().anyMatch(s -> s.contains("Sign in")),
                "TAB moves from email to password and reaches the Sign in button");

        // ---------------------------------------------------------------- small window (980 x 640)
        fx(() -> {
            stage.setWidth(980);
            stage.setHeight(640);
        });
        pause(1000);
        snap("105-small-login");
        AtomicReference<Boolean> brandHidden = new AtomicReference<>();
        fx(() -> brandHidden.set(scene.getRoot().lookupAll(".login-brand").stream().noneMatch(UiSnapshots::shownOnScreen)));
        check(brandHidden.get(), "login hides the brand panel below 1000 px");
        login("asha.rao@college.edu");
        snap("106-small-dashboard");
        fx(() -> shell.open(new com.classsync.view.LectureDetailPage(ctx, shell, l4)));
        pause(1500);
        selectTab("Feedback");
        snap("107-small-lecture-tabs");
        logout();
        fx(() -> {
            stage.setWidth(1440);
            stage.setHeight(900);
        });

    }

    /** Admin dashboard and user management, driven through the real dialogs and buttons. */
    private static void adminScreens() throws Exception {
        login("admin@college.edu");
        User admin = ctx.session().requireUser();
        snap("120-admin-dashboard");
        fx(() -> shell.open(Nav.USERS));
        snap("121-admin-users");

        // add a professor through the real dialog, with a generated password
        later(() -> com.classsync.view.AdminForms.create(ctx, shell, com.classsync.model.Role.PROFESSOR, () -> { }));
        Scene d = awaitDialog();
        fill(d, "Full name, e.g. Arjun Verma", "Dr. Kavita Sharma");
        fill(d, "name@college.edu", "kavita.sharma@college.edu");
        fill(d, "e.g. EMP205", "EMP205");
        fill(d, "e.g. Computer Science", "Computer Science");
        press(d, "Generate");
        AtomicReference<String> generated = new AtomicReference<>();
        fx(() -> generated.set(d.getRoot().lookupAll(".text-field").stream().map(javafx.scene.control.TextField.class::cast)
                .filter(t -> "8+ characters, a letter and a digit".equals(t.getPromptText())).findFirst().orElseThrow().getText()));
        snapDialog(d, "122-dialog-admin-add-user");
        press(d, "Create account");
        pause(2500);
        check(ctx.auth().login("kavita.sharma@college.edu", generated.get().toCharArray()).role() == com.classsync.model.Role.PROFESSOR,
                "add-user dialog created a professor who can sign in with the generated password");

        // reset that professor's password through the real dialog
        var kavita = ctx.admin().list(admin, null, "kavita", true).get(0);
        later(() -> com.classsync.view.AdminForms.resetPassword(ctx, shell, kavita, () -> { }));
        Scene r = awaitDialog();
        fill(r, "8+ characters, a letter and a digit", "Changed#2026");
        press(r, "Set password");
        pause(2500);
        check(ctx.auth().login("kavita.sharma@college.edu", "Changed#2026".toCharArray()) != null,
                "reset-password dialog set the new password");

        // disable the first listed account with its real "Disable" button + confirmation
        fx(() -> shell.refresh());
        pause(2000);
        AtomicReference<Integer> columns = new AtomicReference<>();
        fx(() -> columns.set(((javafx.scene.control.TableView<?>) scene.getRoot().lookup(".table-view")).getColumns().size()));
        check(columns.get() == 8, "refreshing User Management keeps 8 table columns (was duplicating them)");
        // table cells are recycled after a refresh, so scene order is not row order: take the account from the
        // cell that owns the button actually pressed
        AtomicReference<String> firstName = new AtomicReference<>();
        later(() -> {
            javafx.scene.control.Button b = scene.getRoot().lookupAll(".button").stream()
                    .filter(n -> n instanceof javafx.scene.control.Button x && "Disable".equals(x.getText()) && x.isVisible() && !x.isDisabled())
                    .map(javafx.scene.control.Button.class::cast).findFirst()
                    .orElseThrow(() -> new IllegalStateException("No button 'Disable'"));
            javafx.scene.Node n = b;
            while (n != null && !(n instanceof javafx.scene.control.TableCell<?, ?>)) {
                n = n.getParent();
            }
            firstName.set(((com.classsync.dao.AdminDao.UserRow) ((javafx.scene.control.TableCell<?, ?>) n).getItem()).email());
            b.fire();
        });
        Scene c = awaitDialog();
        press(c, "Disable");
        pause(2500);
        boolean disabled = ctx.admin().list(admin, null, firstName.get(), true).stream().noneMatch(x -> x.active());
        check(disabled, "Disable button (after confirmation) disabled " + firstName.get());
        snap("123-admin-users-after");
        var row = ctx.admin().list(admin, null, firstName.get(), true).get(0);
        ctx.admin().setActive(admin, row.id(), true);   // put the demo account back

        fx(() -> Theme.apply(scene, true));
        fx(() -> shell.open(Nav.DASHBOARD));
        snap("124-dark-admin-dashboard");
        fx(() -> Theme.apply(scene, false));
        logout();
    }

    /** Startup error screens (database down, schema behind). Runs last: it damages the test schema record. */
    private static void startupScreens() throws Exception {
        var zone = ctx.lectures().zone();
        AtomicReference<Stage> downStage = new AtomicReference<>();
        AppContext broken = new AppContext(TestDb.config(),
                new com.classsync.config.Database("jdbc:postgresql://127.0.0.1:1/none", "nobody", "x"), Clock.system(zone));
        AtomicReference<Scene> downScene = new AtomicReference<>();
        fx(() -> {
            Stage down = new Stage();
            downStage.set(down);
            Scene s = new Scene(new StackPane(), 1100, 700);
            Theme.apply(s, false);
            down.setScene(s);
            new AppShell(broken, s).showStartup();
            down.show();
            downScene.set(s);
        });
        pause(7000);
        AtomicReference<WritableImage> img = new AtomicReference<>();
        fx(() -> img.set(downScene.get().snapshot(null)));
        save(img.get(), out.resolve("108-startup-database-down.png"));
        AtomicReference<String> downText = new AtomicReference<>();
        fx(() -> downText.set(downScene.get().getRoot().lookupAll(".label").stream()
                .map(n -> ((javafx.scene.control.Label) n).getText()).reduce("", (x, y) -> x + " | " + y)));
        fx(() -> downStage.get().close());
        broken.background().shutdown();
        check(downText.get().toLowerCase().contains("connect"), "startup shows the cannot-connect screen when PostgreSQL is unreachable");

        // outdated schema: remove the newest migration record in the DISPOSABLE test database
        try (var con = ctx.database().open(); var st = con.createStatement()) {
            st.executeUpdate("DELETE FROM schema_migrations WHERE version = (SELECT max(version) FROM schema_migrations)");
        }
        fx(() -> shell.showStartup());
        pause(3000);
        snap("109-startup-schema-outdated");
        AtomicReference<String> schemaText = new AtomicReference<>();
        fx(() -> schemaText.set(scene.getRoot().lookupAll(".label").stream()
                .map(n -> ((javafx.scene.control.Label) n).getText()).reduce("", (x, y) -> x + " | " + y)));
        check(schemaText.get().toLowerCase().contains("update"), "startup asks for a schema update when migrations are behind");
    }

    interface Step {
        void run() throws Exception;
    }

    private static void run(String only, String name, Step step) throws Exception {
        if (only.equals("all") || java.util.Arrays.asList(only.split(",")).contains(name)) {
            step.run();
        }
    }

    private static void login(String email) throws Exception {
        User u = ctx.auth().login(email, DemoData.PASSWORD.toCharArray());
        fx(() -> shell.onSignedIn(u));
        pause(2500);
    }

    private static void logout() throws Exception {
        fx(() -> shell.logout());
        pause(800);
    }

    private static void openFirstUpcomingLecture() throws Exception {
        User u = ctx.session().requireUser();
        var upcoming = ctx.lectures().upcoming(u, 5).stream()
                .filter(l -> !l.preparation().isBlank()).findFirst().orElseThrow();
        fx(() -> shell.open(new com.classsync.view.LectureDetailPage(ctx, shell, upcoming.id())));
    }

    private static long courseId(User u, String code) throws Exception {
        return ctx.courses().myCourses(u, false).stream().filter(c -> c.code().equals(code)).findFirst().orElseThrow().id();
    }

    private static void selectTab(String text) throws Exception {
        fx(() -> scene.getRoot().lookupAll(".tab-pane").stream()
                .map(javafx.scene.control.TabPane.class::cast)
                .forEach(tp -> tp.getTabs().stream().filter(t -> t.getText().startsWith(text)).findFirst()
                        .ifPresent(t -> tp.getSelectionModel().select(t))));
    }

    private static void clickButton(String text) throws Exception {
        fx(() -> scene.getRoot().lookupAll(".button").stream()
                .filter(n -> n instanceof javafx.scene.control.Button b && text.equals(b.getText()) && b.isVisible())
                .map(javafx.scene.control.Button.class::cast)
                .findFirst().orElseThrow(() -> new IllegalStateException("No button '" + text + "'")).fire());
    }

    private static String codeOnScreen() throws Exception {
        AtomicReference<String> code = new AtomicReference<>();
        fx(() -> code.set(((javafx.scene.control.Label) scene.getRoot().lookup(".code-display")).getText().replace(" ", "")));
        return code.get();
    }

    private static long quizId(User u, String title) throws Exception {
        return ctx.quizzes().list(u, null, null).stream().filter(q -> q.title().equals(title)).findFirst().orElseThrow().id();
    }

    private static void openCourse(String code) throws Exception {
        User u = ctx.session().requireUser();
        long id = ctx.courses().myCourses(u, false).stream().filter(c -> c.code().equals(code)).findFirst().orElseThrow().id();
        fx(() -> shell.open(new com.classsync.view.CourseDetailPage(ctx, shell, id)));
    }

    private static void snap(String name) throws Exception {
        pause(1800);
        AtomicReference<WritableImage> img = new AtomicReference<>();
        fx(() -> img.set(scene.snapshot(null)));
        save(img.get(), out.resolve(name + ".png"));
        System.out.println("  saved " + name);
    }

    private static void save(WritableImage image, Path file) throws Exception {
        int w = (int) image.getWidth();
        int h = (int) image.getHeight();
        BufferedImage buffered = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        PixelReader reader = image.getPixelReader();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                buffered.setRGB(x, y, reader.getArgb(x, y));
            }
        }
        ImageIO.write(buffered, "png", file.toFile());
    }

    /** Runs code on the JavaFX thread and waits for it. */
    static void fx(Runnable r) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                r.run();
            } catch (Throwable t) {
                error.set(t);
            } finally {
                done.countDown();
            }
        });
        done.await();
        if (error.get() != null) {
            throw new RuntimeException(error.get());
        }
    }

    static void pause(long ms) throws InterruptedException {
        Thread.sleep(ms);
    }

    @SuppressWarnings("unused")
    private static Consumer<Throwable> ignore() {
        return t -> { };
    }
}
