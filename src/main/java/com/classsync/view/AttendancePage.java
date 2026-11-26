package com.classsync.view;

import com.classsync.AppContext;
import com.classsync.dao.AttendanceDao;
import com.classsync.model.AttendanceSession;
import com.classsync.model.Course;
import com.classsync.model.Lecture;
import com.classsync.model.LectureStatus;
import com.classsync.model.Role;
import com.classsync.model.User;
import com.classsync.service.AttendanceService;
import com.classsync.service.AttendanceService.CourseReport;
import com.classsync.service.AttendanceService.StudentSummary;
import com.classsync.service.Errors;
import javafx.collections.FXCollections;
import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.List;

/**
 * Students/CRs: mark attendance for open sessions, see overall and per-course percentages and history.
 * Professors: per-course report with lectures awaiting finalization and low-attendance students.
 */
public class AttendancePage extends Page {

    public AttendancePage(AppContext ctx, AppShell shell) {
        super(ctx, shell);
    }

    @Override
    public Nav nav() {
        return Nav.ATTENDANCE;
    }

    @Override
    public Node build() {
        User u = user();
        return u.role() == Role.PROFESSOR ? professor(u) : student(u);
    }

    // ---------------------------------------------------------------- student / CR

    private Node student(User u) {
        VBox body = new VBox(20, Ui.pageHeader("Attendance",
                "Counted lectures are finalized and not cancelled. Below " + Ui.percent(AttendanceService.LOW_ATTENDANCE)
                        + " shows a warning."), Ui.loading("Loading attendance…"));
        record Data(List<AttendanceSession> open, StudentSummary summary) { }
        load(() -> new Data(ctx.attendance().openSessions(u), ctx.attendance().summary(u)), d -> {
            body.getChildren().remove(1);
            List<AttendanceSession> unmarked = d.open();
            VBox openList = new VBox(10);
            if (unmarked.isEmpty()) {
                openList.getChildren().add(Ui.label("No attendance session is open in your courses right now.", "faint"));
            }
            for (AttendanceSession s : unmarked) {
                openList.getChildren().add(AttendanceWidgets.codeEntry(ctx, shell, s, shell::refresh));
            }
            StudentSummary s = d.summary();
            boolean low = AttendanceService.isLow(s.present(), s.counted());
            HBox stats = new HBox(16,
                    Ui.statCard(Icons.ATTENDANCE, AttendanceWidgets.percent(s.overall()), "Overall attendance",
                            s.counted() == 0 ? "No finalized lectures yet" : s.present() + " of " + s.counted() + " counted lectures",
                            low ? "stat-amber" : "stat-green"),
                    Ui.statCard(Icons.CHECK, String.valueOf(s.present()), "Lectures attended", null, "stat-teal"),
                    Ui.statCard(Icons.X, String.valueOf(s.counted() - s.present()), "Lectures missed", null, "stat-violet"));
            VBox courses = new VBox(8);
            for (AttendanceDao.CourseTotals c : s.courses()) {
                courses.getChildren().add(AttendanceWidgets.courseRow(c.courseCode(), c.courseName(), c.present(), c.counted()));
            }
            body.getChildren().addAll(Ui.card("Mark attendance", openList), stats, Ui.card("By course", courses),
                    Ui.card("History", historyTable(s.history())));
        }, e -> body.getChildren().set(1, Ui.errorState(Errors.userMessage(e), shell::refresh)));
        return Ui.page(body);
    }

    private Node historyTable(List<AttendanceDao.HistoryRow> rows) {
        TableView<AttendanceDao.HistoryRow> table = new TableView<>(FXCollections.observableArrayList(rows));
        table.setPlaceholder(Ui.label("No finalized lectures yet.", "faint"));
        var zone = ctx.lectures().zone();
        table.getColumns().add(CourseDetailPage.column("Date", r -> r.startsAt().atZone(zone).format(Ui.DATE), 150));
        table.getColumns().add(CourseDetailPage.column("Course", AttendanceDao.HistoryRow::courseCode, 90));
        table.getColumns().add(CourseDetailPage.column("Lecture", r -> "L" + r.lectureNo() + " · " + r.topic(), 300));
        TableColumn<AttendanceDao.HistoryRow, AttendanceDao.HistoryRow> status = new TableColumn<>("Status");
        status.setCellValueFactory(cd -> new javafx.beans.property.SimpleObjectProperty<>(cd.getValue()));
        status.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(AttendanceDao.HistoryRow r, boolean empty) {
                super.updateItem(r, empty);
                setGraphic(empty || r == null ? null : Ui.row(6, AttendanceWidgets.statusBadge(r.status()),
                        r.corrected() ? Ui.badge("Corrected", "info") : Ui.label("")));
            }
        });
        status.setPrefWidth(200);
        table.getColumns().add(status);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPrefHeight(Math.min(420, 44 + 40 * Math.max(2, rows.size())));
        table.setRowFactory(tv -> {
            var row = new javafx.scene.control.TableRow<AttendanceDao.HistoryRow>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) {
                    shell.open(new LectureDetailPage(ctx, shell, row.getItem().lectureId()));
                }
            });
            return row;
        });
        return table;
    }

    // ---------------------------------------------------------------- professor

    private Node professor(User u) {
        ComboBox<Course> course = new ComboBox<>();
        course.setConverter(QuizForms.converter(Course::display));
        VBox report = new VBox(20, Ui.loading("Loading courses…"));
        course.setOnAction(e -> {
            if (course.getValue() != null) {
                showReport(u, course.getValue(), report);
            }
        });
        load(() -> ctx.courses().myCourses(u, false), mine -> {
            course.getItems().setAll(mine);
            if (mine.isEmpty()) {
                report.getChildren().setAll(Ui.emptyState(Icons.COURSES, "No courses yet", null, null));
            } else {
                course.setValue(mine.get(0));
            }
        });
        return Ui.page(new VBox(16, Ui.pageHeader("Attendance",
                "Start sessions from a lecture's Attendance tab. Reports count finalized, non-cancelled lectures."),
                Ui.row(8, Ui.label("Course", "field-label"), course), report));
    }

    private void showReport(User u, Course c, VBox box) {
        box.getChildren().setAll(Ui.loading("Building report…"));
        record Data(CourseReport report, List<Lecture> pending) { }
        load(() -> {
            CourseReport r = ctx.attendance().courseReport(u, c.id());
            var now = ctx.clock().instant();
            List<Lecture> pending = ctx.lectures().forCourse(u, c.id()).stream()
                    .filter(l -> !l.attendanceFinalized() && !l.startsAt().isAfter(now)
                            && (l.status() == LectureStatus.PUBLISHED || l.status() == LectureStatus.COMPLETED))
                    .toList();
            return new Data(r, pending);
        }, d -> {
            CourseReport r = d.report();
            long low = r.students().stream().filter(s -> AttendanceService.isLow(s.present(), s.counted())).count();
            HBox stats = new HBox(16,
                    Ui.statCard(Icons.ATTENDANCE, AttendanceWidgets.percent(r.average()), "Average attendance",
                            r.lectures().isEmpty() ? "No finalized lectures yet" : "Across " + r.lectures().size() + " finalized lectures", null),
                    Ui.statCard(Icons.CLOCK, String.valueOf(r.awaitingFinalization()), "Awaiting finalization",
                            "Lectures that have started", "stat-amber"),
                    Ui.statCard(Icons.ALERT, String.valueOf(low), "Below " + Ui.percent(AttendanceService.LOW_ATTENDANCE),
                            "Students with a warning", "stat-violet"));

            VBox pending = new VBox(8);
            if (d.pending().isEmpty()) {
                pending.getChildren().add(Ui.label("Every lecture that has started is finalized.", "faint"));
            }
            for (Lecture l : d.pending()) {
                pending.getChildren().add(Widgets.lectureRow(l, ctx.lectures().zone(),
                        () -> shell.open(new LectureDetailPage(ctx, shell, l.id()))));
            }

            TableView<AttendanceDao.LectureTotals> lectures = new TableView<>(FXCollections.observableArrayList(r.lectures()));
            lectures.setPlaceholder(Ui.label("No finalized lectures yet.", "faint"));
            var zone = ctx.lectures().zone();
            lectures.getColumns().add(CourseDetailPage.column("Lecture", l -> "L" + l.lectureNo() + " · " + l.topic(), 280));
            lectures.getColumns().add(CourseDetailPage.column("Date", l -> l.startsAt().atZone(zone).format(Ui.DATE), 150));
            lectures.getColumns().add(CourseDetailPage.column("Present", l -> l.present() + " / " + l.total(), 100));
            lectures.getColumns().add(CourseDetailPage.column("Rate",
                    l -> AttendanceWidgets.percent(AttendanceService.fraction(l.present(), l.total())), 80));
            lectures.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
            lectures.setPrefHeight(Math.min(380, 44 + 40 * Math.max(2, r.lectures().size())));
            lectures.setRowFactory(tv -> {
                var row = new javafx.scene.control.TableRow<AttendanceDao.LectureTotals>();
                row.setOnMouseClicked(e -> {
                    if (e.getClickCount() == 2 && !row.isEmpty()) {
                        shell.open(new LectureDetailPage(ctx, shell, row.getItem().lectureId()));
                    }
                });
                return row;
            });

            VBox students = new VBox(8);
            for (AttendanceDao.StudentTotals s : r.students().stream()
                    .sorted(java.util.Comparator.comparingDouble(
                            (AttendanceDao.StudentTotals s) -> AttendanceService.fraction(s.present(), s.counted()).orElse(2)))
                    .toList()) {
                students.getChildren().add(AttendanceWidgets.courseRow(s.name(), s.enrollmentNo(), s.present(), s.counted()));
            }
            if (r.students().isEmpty()) {
                students.getChildren().add(Ui.label("No students enrolled.", "faint"));
            }
            box.getChildren().setAll(stats, Ui.card("Lectures awaiting finalization", pending),
                    Ui.card("Finalized lectures (double-click to open)", lectures),
                    Ui.card("Students — lowest attendance first", students));
        }, e -> box.getChildren().setAll(Ui.errorState(Errors.userMessage(e), () -> showReport(u, c, box))));
    }
}
