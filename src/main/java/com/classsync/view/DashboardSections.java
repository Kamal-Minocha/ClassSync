package com.classsync.view;

import com.classsync.AppContext;
import com.classsync.model.Role;
import com.classsync.model.User;
import com.classsync.service.Errors;
import com.classsync.service.QuizAnalyticsService.QuizSummary;
import com.classsync.service.QuizService.StudentQuiz;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;

/**
 * Dashboard sections contributed by later modules (pending quizzes, attendance, approvals,
 * feedback, doubts, streaks...). Each is added here only once its module is implemented.
 * Every section loads its own data in the background and shows a loading state meanwhile.
 */
final class DashboardSections {

    private DashboardSections() {
    }

    static List<Node> moduleSections(AppContext ctx, AppShell shell, User u) {
        List<Node> sections = new ArrayList<>();
        sections.add(progress(ctx, shell, u));
        sections.add(collaboration(ctx, shell, u));
        sections.add(attendance(ctx, shell, u));
        sections.add(quizzes(ctx, shell, u));
        return sections;
    }

    // ---------------------------------------------------------------- Phase 9: learning progress / syllabus completion

    private static Node progress(AppContext ctx, AppShell shell, User u) {
        VBox body = new VBox(8, Ui.loading("Loading progress…"));
        if (u.role() == Role.PROFESSOR) {
            Button open = Ui.button("Planner", Icons.CHEVRON_RIGHT, "btn-ghost");
            open.setOnAction(e -> shell.open(Nav.PLANNER));
            ctx.background().run(() -> {
                List<HBox> rows = new ArrayList<>();
                for (var c : ctx.courses().myCourses(u, false)) {
                    rows.add(PlannerPage.completionRow(c, ctx.planner().plan(u, c.id())));
                }
                return rows;
            }, rows -> {
                body.getChildren().setAll(rows);
                if (rows.isEmpty()) {
                    body.getChildren().add(Ui.label("No courses yet.", "faint"));
                }
            }, e -> body.getChildren().setAll(Ui.label(Errors.userMessage(e), "error-text")));
            return Ui.card(Ui.row(8, Ui.label("Syllabus completion", "card-title"), Ui.spacer(), open), body);
        }
        record Data(int[] streak, long badges, long openTasks, long overdue) { }
        ctx.background().run(() -> {
            ctx.progress().evaluateBadges(u);
            var tasks = ctx.study().tasks(u);
            var today = java.time.LocalDate.now(ctx.clock());
            long badges = ctx.progress().performance(u).badges().stream().filter(b -> b.awardedAt() != null).count();
            return new Data(ctx.progress().streak(u), badges, tasks.stream().filter(t -> t.doneAt() == null).count(),
                    tasks.stream().filter(t -> t.doneAt() == null && t.dueDate() != null && t.dueDate().isBefore(today)).count());
        }, d -> {
            HBox row = new HBox(16,
                    Ui.clickable(Ui.statCard(Icons.FLAME, QuizWidgets.plural(d.streak()[0], "day"), "Learning streak",
                            "Longest " + QuizWidgets.plural(d.streak()[1], "day"), "stat-amber"), () -> shell.open(Nav.PERFORMANCE)),
                    Ui.clickable(Ui.statCard(Icons.TROPHY, String.valueOf(d.badges()), "Badges earned", "Out of 5", "stat-violet"),
                            () -> shell.open(Nav.PERFORMANCE)),
                    Ui.clickable(Ui.statCard(Icons.BOOKMARK, String.valueOf(d.openTasks()), "Open study tasks",
                            d.overdue() > 0 ? d.overdue() + " overdue" : "None overdue", "stat-teal"), () -> shell.open(Nav.BOOKMARKS)),
                    Ui.clickable(Ui.statCard(Icons.REVISION, "Revise", "Mistakes & practice", "Completed lectures and released answers", null),
                            () -> shell.open(Nav.REVISION)));
            body.getChildren().setAll(row);
        }, e -> body.getChildren().setAll(Ui.label(Errors.userMessage(e), "error-text")));
        return body;
    }

    // ---------------------------------------------------------------- Phase 8: announcements, feedback, notes, doubts

    private static Node collaboration(AppContext ctx, AppShell shell, User u) {
        VBox news = new VBox(8, Ui.loading("Loading announcements…"));
        Button allNews = Ui.button("All", Icons.CHEVRON_RIGHT, "btn-ghost");
        allNews.setOnAction(e -> shell.open(Nav.ANNOUNCEMENTS));
        VBox newsCard = Ui.card(Ui.row(8, Ui.label("Announcements", "card-title"), Ui.spacer(), allNews), news);
        HBox.setHgrow(newsCard, Priority.ALWAYS);
        VBox side = new VBox(8, Ui.loading("Loading…"));
        VBox sideCard = Ui.card(u.role() == Role.PROFESSOR ? "Needs your attention" : "To do", side);
        sideCard.setPrefWidth(420);
        sideCard.setMinWidth(320);

        record Data(List<com.classsync.model.Announcement> news, List<com.classsync.dao.FeedbackDao.PendingRow> feedback,
                    int notesToReview, List<com.classsync.model.Discussion> openDoubts,
                    List<com.classsync.dao.FeedbackDao.TrendRow> recentFeedback) { }
        ctx.background().run(() -> {
            List<com.classsync.dao.FeedbackDao.TrendRow> recent = new ArrayList<>();
            if (u.role() == Role.PROFESSOR) {
                for (var c : ctx.courses().myCourses(u, false)) {
                    var trend = ctx.feedback().trend(u, c.id());
                    if (!trend.isEmpty()) {
                        recent.add(trend.get(trend.size() - 1));
                    }
                }
            }
            var doubts = ctx.discussions().list(u, new com.classsync.service.DiscussionService.Query(null, null, null,
                    true, u.role().isLearner(), 4));
            return new Data(ctx.announcements().list(u, null, 3), ctx.feedback().pending(u),
                    ctx.notes().reviewQueue(u).size(), doubts, recent);
        }, d -> {
            news.getChildren().clear();
            if (d.news().isEmpty()) {
                news.getChildren().add(Ui.label("No announcements.", "faint"));
            }
            for (var a : d.news()) {
                VBox text = new VBox(2, Ui.row(6, Ui.badge(a.courseCode(), "primary"), AnnouncementsPage.categoryBadge(a.category())),
                        Ui.wrapping(a.title(), "body-strong"),
                        Ui.label(a.authorName() + " · " + a.createdAt().atZone(ctx.lectures().zone()).format(Ui.DATE_TIME), "small", "faint"));
                text.getStyleClass().addAll("list-row", "list-row-clickable");
                text.setOnMouseClicked(e -> shell.open(Nav.ANNOUNCEMENTS));
                news.getChildren().add(text);
            }
            side.getChildren().clear();
            if (d.notesToReview() > 0) {
                side.getChildren().add(todo(Icons.NOTE, QuizWidgets.plural(d.notesToReview(), "note") + " waiting for your review",
                        () -> shell.open(Nav.NOTES)));
            }
            for (var f : d.feedback().stream().limit(3).toList()) {
                side.getChildren().add(todo(Icons.STAR, "Give feedback: " + f.courseCode() + " L" + f.lectureNo() + " · " + f.topic(),
                        () -> shell.open(new LectureDetailPage(ctx, shell, f.lectureId()))));
            }
            for (var t : d.recentFeedback()) {
                side.getChildren().add(todo(Icons.STAR, "Feedback L" + t.lectureNo() + " · " + t.topic() + ": "
                                + "%.1f/5 from %s".formatted(t.summary().overall(), QuizWidgets.plural(t.summary().count(), "student")),
                        () -> shell.open(new LectureDetailPage(ctx, shell, t.lectureId()))));
            }
            for (var q : d.openDoubts()) {
                side.getChildren().add(todo(Icons.CHAT, (u.role().isLearner() ? "Your open doubt: " : "Unresolved: ")
                        + q.courseCode() + " · " + q.title(), () -> shell.open(new DiscussionThreadPage(ctx, shell, q.id()))));
            }
            if (side.getChildren().isEmpty()) {
                side.getChildren().add(Ui.label("Nothing waiting for you.", "faint"));
            }
        }, e -> news.getChildren().setAll(Ui.label(Errors.userMessage(e), "error-text")));
        return new HBox(20, newsCard, sideCard);
    }

    private static HBox todo(String icon, String text, Runnable open) {
        HBox row = Ui.row(10, Icons.of(icon, 18), Ui.wrapping(text, "small"));
        row.getStyleClass().addAll("list-row", "list-row-clickable");
        row.setOnMouseClicked(e -> open.run());
        return row;
    }

    // ---------------------------------------------------------------- Module 6: attendance

    private static Node attendance(AppContext ctx, AppShell shell, User u) {
        VBox body = new VBox(8, Ui.loading("Loading attendance…"));
        Button open = Ui.button("Attendance", Icons.CHEVRON_RIGHT, "btn-ghost");
        open.setOnAction(e -> shell.open(Nav.ATTENDANCE));
        if (u.role() == Role.PROFESSOR) {
            ctx.background().run(() -> ctx.attendance().overview(u), courses -> {
                body.getChildren().clear();
                if (courses.isEmpty()) {
                    body.getChildren().add(Ui.label("No courses yet.", "faint"));
                }
                for (var c : courses) {
                    VBox text = new VBox(2, Ui.label(c.courseCode(), "body-strong"), Ui.label(
                            (c.finalizedLectures() == 0 ? "No finalized lectures yet" : "Average " + AttendanceWidgets.percent(c.average())
                                    + " over " + QuizWidgets.plural(c.finalizedLectures(), "lecture")), "small", "muted"));
                    HBox.setHgrow(text, Priority.ALWAYS);
                    HBox row = Ui.row(10, text);
                    if (c.awaitingFinalization() > 0) {
                        row.getChildren().add(Ui.badge(c.awaitingFinalization() + " to finalize", "warning"));
                    }
                    if (c.lowAttendanceStudents() > 0) {
                        row.getChildren().add(Ui.badge(QuizWidgets.plural(c.lowAttendanceStudents(), "student") + " below "
                                + Ui.percent(com.classsync.service.AttendanceService.LOW_ATTENDANCE), "danger"));
                    }
                    row.getStyleClass().addAll("list-row", "list-row-clickable");
                    row.setOnMouseClicked(e -> shell.open(Nav.ATTENDANCE));
                    body.getChildren().add(row);
                }
            }, e -> body.getChildren().setAll(Ui.label(Errors.userMessage(e), "error-text")));
            return Ui.card(Ui.row(8, Ui.label("Attendance overview", "card-title"), Ui.spacer(), open), body);
        }
        record Data(List<com.classsync.model.AttendanceSession> open,
                    com.classsync.service.AttendanceService.StudentSummary summary) { }
        ctx.background().run(() -> new Data(ctx.attendance().openSessions(u), ctx.attendance().summary(u)), d -> {
            body.getChildren().clear();
            for (var s : d.open()) {
                body.getChildren().add(AttendanceWidgets.codeEntry(ctx, shell, s, shell::refresh));
            }
            var s = d.summary();
            body.getChildren().add(Ui.row(10, Ui.label("Overall", "body-strong"), Ui.label(AttendanceWidgets.percent(s.overall()), "h2"),
                    Ui.label(s.counted() == 0 ? "no finalized lectures yet" : s.present() + " of " + s.counted() + " lectures", "small", "muted")));
            for (var c : s.courses()) {
                body.getChildren().add(AttendanceWidgets.courseRow(c.courseCode(), null, c.present(), c.counted()));
            }
        }, e -> body.getChildren().setAll(Ui.label(Errors.userMessage(e), "error-text")));
        return Ui.card(Ui.row(8, Ui.label("Attendance", "card-title"), Ui.spacer(), open), body);
    }

    // ---------------------------------------------------------------- Module 5: quizzes

    private static Node quizzes(AppContext ctx, AppShell shell, User u) {
        return u.role() == Role.PROFESSOR ? professorQuizzes(ctx, shell, u) : studentQuizzes(ctx, shell, u);
    }

    private static Node studentQuizzes(AppContext ctx, AppShell shell, User u) {
        VBox pending = new VBox(10, Ui.loading("Loading quizzes…"));
        Button all = Ui.button("All quizzes", Icons.CHEVRON_RIGHT, "btn-ghost");
        all.setOnAction(e -> shell.open(Nav.QUIZZES));
        VBox pendingCard = Ui.card(Ui.row(8, Ui.label("Pending quizzes", "card-title"), Ui.spacer(), all), pending);
        HBox.setHgrow(pendingCard, Priority.ALWAYS);
        VBox weakHolder = new VBox(Ui.card("Weak topics", Ui.loading("Analysing your results…")));
        HBox row = new HBox(20, pendingCard, weakHolder);

        record Data(List<StudentQuiz> pending, List<com.classsync.service.QuizAnalyticsService.TopicInsight> topics) { }
        ctx.background().run(() -> new Data(ctx.quizzes().pending(u), ctx.quizAnalytics().weakTopicsForStudent(u)), d -> {
            pending.getChildren().clear();
            if (d.pending().isEmpty()) {
                pending.getChildren().add(Ui.label("You are all caught up — no open quizzes.", "faint"));
            }
            for (StudentQuiz sq : d.pending().stream().limit(3).toList()) {
                pending.getChildren().add(QuizWidgets.studentRow(ctx, shell, sq));
            }
            weakHolder.getChildren().setAll(QuizzesPage.weakTopicsCard(d.topics()));
        }, e -> pending.getChildren().setAll(Ui.label(Errors.userMessage(e), "error-text")));
        return row;
    }

    private static Node professorQuizzes(AppContext ctx, AppShell shell, User u) {
        VBox list = new VBox(8, Ui.loading("Loading quiz analytics…"));
        Button open = Ui.button("Quizzes", Icons.CHEVRON_RIGHT, "btn-ghost");
        open.setOnAction(e -> shell.open(Nav.QUIZZES));
        ctx.background().run(() -> ctx.quizAnalytics().recentSummaries(u, 4), items -> {
            list.getChildren().clear();
            if (items.isEmpty()) {
                list.getChildren().add(Ui.label("No quizzes yet. Create one from a lecture or the Quizzes page.", "faint"));
            }
            for (QuizSummary s : items) {
                String participation = s.enrolled() == 0 ? "no students" : s.finished() + "/" + s.enrolled() + " submitted";
                String average = s.averageFraction().isPresent() ? "avg " + Ui.percent(s.averageFraction().getAsDouble()) : "no scores yet";
                VBox text = new VBox(2, Ui.label(s.quiz().title(), "body-strong"),
                        Ui.label(s.quiz().courseCode() + "  ·  " + participation + "  ·  " + average, "small", "muted"));
                HBox.setHgrow(text, Priority.ALWAYS);
                HBox row = Ui.row(10, text, QuizWidgets.statusBadge(s.quiz()));
                row.getStyleClass().addAll("list-row", "list-row-clickable");
                row.setOnMouseClicked(e -> shell.open(new QuizEditorPage(ctx, shell, s.quiz().id())));
                list.getChildren().add(row);
            }
        }, e -> list.getChildren().setAll(Ui.label(Errors.userMessage(e), "error-text")));
        return Ui.card(Ui.row(8, Ui.label("Quiz analytics", "card-title"), Ui.spacer(), open), list);
    }
}
