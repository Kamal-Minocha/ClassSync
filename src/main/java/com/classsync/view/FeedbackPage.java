package com.classsync.view;

import com.classsync.AppContext;
import com.classsync.dao.FeedbackDao;
import com.classsync.model.Course;
import com.classsync.model.Role;
import com.classsync.model.User;
import javafx.scene.Node;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;

/**
 * Students: lectures waiting for their feedback. Professors: per-course trend of lecture ratings,
 * linking to each lecture's full report (the lecture's Feedback tab).
 */
public class FeedbackPage extends Page {

    public FeedbackPage(AppContext ctx, AppShell shell) {
        super(ctx, shell);
    }

    @Override
    public Nav nav() {
        return Nav.FEEDBACK;
    }

    @Override
    public Node build() {
        User u = user();
        return u.role() == Role.PROFESSOR ? professor(u) : student(u);
    }

    private Node student(User u) {
        VBox list = new VBox(10, Ui.loading("Loading…"));
        load(() -> ctx.feedback().pending(u), rows -> {
            list.getChildren().clear();
            if (rows.isEmpty()) {
                list.getChildren().add(Ui.emptyState(Icons.STAR, "No feedback requests right now",
                        "When a professor opens feedback for a lecture you attended, it appears here.", null));
            }
            for (FeedbackDao.PendingRow r : rows) {
                VBox text = new VBox(2, Ui.label("L" + r.lectureNo() + " · " + r.topic(), "body-strong"),
                        Ui.label(r.courseCode() + " · " + r.startsAt().atZone(ctx.lectures().zone()).format(Ui.DATE), "small", "muted"));
                HBox.setHgrow(text, Priority.ALWAYS);
                Button give = Ui.primary("Give feedback", Icons.STAR);
                give.setOnAction(e -> ctx.background().run(() -> ctx.lectures().get(u, r.lectureId()),
                        l -> CollabForms.feedback(ctx, shell, l, null, shell::refresh), shell::showError));
                HBox row = Ui.row(12, Icons.of(Icons.STAR, 20), text, give);
                row.getStyleClass().add("list-row");
                list.getChildren().add(row);
            }
        });
        return Ui.page(new VBox(16, Ui.pageHeader("Feedback",
                "Rate understanding, clarity, pace and usefulness. You can edit your answer while feedback stays open."),
                Ui.card("Waiting for your feedback", list)));
    }

    private Node professor(User u) {
        ComboBox<Course> course = new ComboBox<>();
        course.setConverter(QuizForms.converter(Course::display));
        VBox body = new VBox(20, Ui.loading("Loading courses…"));
        course.setOnAction(e -> {
            if (course.getValue() != null) {
                showTrend(u, course.getValue(), body);
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
        return Ui.page(new VBox(16, Ui.pageHeader("Feedback",
                        "Open feedback and choose anonymity on each lecture's Feedback tab. Averages are 1–5; no responses shows “—”."),
                Ui.row(8, Ui.label("Course", "field-label"), course), body));
    }

    private void showTrend(User u, Course c, VBox body) {
        body.getChildren().setAll(Ui.loading("Loading feedback…"));
        load(() -> ctx.feedback().trend(u, c.id()), rows -> {
            if (rows.isEmpty()) {
                body.getChildren().setAll(Ui.emptyState(Icons.STAR, "No feedback yet for " + c.code(),
                        "Open feedback from a lecture's Feedback tab after the lecture.", null));
                return;
            }
            body.getChildren().setAll(Ui.card("Ratings by lecture (average, 1–5)", trendChart(rows)), lectureList(rows));
        });
    }

    private Node trendChart(List<FeedbackDao.TrendRow> rows) {
        CategoryAxis x = new CategoryAxis();
        NumberAxis y = new NumberAxis(1, 5, 1);
        LineChart<String, Number> chart = new LineChart<>(x, y);
        chart.setAnimated(false);
        chart.setPrefHeight(280);
        String[] names = {"Understanding", "Clarity", "Pace", "Usefulness"};
        for (int i = 0; i < names.length; i++) {
            XYChart.Series<String, Number> s = new XYChart.Series<>();
            s.setName(names[i]);
            for (FeedbackDao.TrendRow r : rows) {
                var sum = r.summary();
                double v = switch (i) {
                    case 0 -> sum.understanding();
                    case 1 -> sum.clarity();
                    case 2 -> sum.pace();
                    default -> sum.usefulness();
                };
                s.getData().add(new XYChart.Data<>("L" + r.lectureNo(), Math.round(v * 10) / 10.0));
            }
            chart.getData().add(s);
        }
        return chart;
    }

    private Node lectureList(List<FeedbackDao.TrendRow> rows) {
        VBox list = new VBox(8);
        for (FeedbackDao.TrendRow r : rows.reversed()) {
            VBox text = new VBox(2, Ui.label("L" + r.lectureNo() + " · " + r.topic(), "body-strong"),
                    Ui.label(r.startsAt().atZone(ctx.lectures().zone()).format(Ui.DATE) + " · "
                            + QuizWidgets.plural(r.summary().count(), "response"), "small", "muted"));
            HBox.setHgrow(text, Priority.ALWAYS);
            HBox row = Ui.row(12, text, Ui.label("%.1f / 5".formatted(r.summary().overall()), "h3"));
            row.getStyleClass().addAll("list-row", "list-row-clickable");
            row.setOnMouseClicked(e -> shell.open(new LectureDetailPage(ctx, shell, r.lectureId())));
            list.getChildren().add(row);
        }
        return Ui.card("Lectures (newest first) — open one for comments", list);
    }
}
