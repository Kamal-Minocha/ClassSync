package com.classsync.view;

import com.classsync.AppContext;
import com.classsync.dao.ProgressDao;
import com.classsync.model.User;
import com.classsync.service.ProgressService.Performance;
import javafx.scene.Node;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Academic performance and achievements: released official quiz results, attendance, learning streak,
 * weekly activity, weak topics and badges. Badges are rewards only — they never change marks.
 */
public class PerformancePage extends Page {

    public PerformancePage(AppContext ctx, AppShell shell) {
        super(ctx, shell);
    }

    @Override
    public Nav nav() {
        return Nav.PERFORMANCE;
    }

    @Override
    public Node build() {
        User u = user();
        StackPane root = new StackPane(Ui.loading("Calculating your progress…"));
        load(() -> ctx.progress().performance(u), p -> {
            for (String code : p.newlyAwarded()) {
                p.badges().stream().filter(b -> b.code().equals(code)).findFirst()
                        .ifPresent(b -> shell.toast().success("New badge: " + b.name() + "!"));
            }
            root.getChildren().setAll(Ui.page(content(p)));
        });
        return root;
    }

    private Node content(Performance p) {
        var att = p.attendance();
        HBox stats = new HBox(16,
                Ui.statCard(Icons.QUIZ, p.quizAverage().isPresent() ? Ui.percent(p.quizAverage().getAsDouble()) : "—",
                        "Quiz average", p.scores().isEmpty() ? "No released official results yet"
                                : "Over " + QuizWidgets.plural(p.scores().size(), "official quiz", "official quizzes"), null),
                Ui.statCard(Icons.ATTENDANCE, AttendanceWidgets.percent(att.overall()), "Attendance",
                        att.counted() == 0 ? "No finalized lectures yet" : att.present() + " of " + att.counted() + " lectures", "stat-teal"),
                Ui.statCard(Icons.FLAME, QuizWidgets.plural(p.currentStreak(), "day"), "Current streak",
                        "Longest: " + QuizWidgets.plural(p.longestStreak(), "day"), "stat-amber"),
                Ui.statCard(Icons.TROPHY, p.badges().stream().filter(b -> b.awardedAt() != null).count() + " / " + p.badges().size(),
                        "Badges", null, "stat-violet"));

        VBox scoresCard = Ui.card("Official quiz scores (%)", p.scores().isEmpty()
                ? Ui.label("Scores appear here when results are released.", "faint") : scoreChart(p));
        VBox weekCard = Ui.card("Learning activity per week", weekChart(p),
                Ui.wrapping("Counts quiz submissions, code attendance, feedback, notes, doubts, replies, revisions and finished "
                        + "tasks. Opening pages does not count.", "small", "faint"));
        HBox.setHgrow(scoresCard, Priority.ALWAYS);
        HBox.setHgrow(weekCard, Priority.ALWAYS);
        scoresCard.setPrefWidth(500);
        weekCard.setPrefWidth(500);

        FlowPane badges = new FlowPane(12, 12);
        for (ProgressDao.Badge b : p.badges()) {
            VBox card = new VBox(4, Icons.of(Icons.TROPHY, 24), Ui.label(b.name(), "body-strong"),
                    Ui.wrapping(b.description(), "small", "muted"),
                    Ui.label(b.awardedAt() == null ? "Not earned yet"
                            : "Earned " + b.awardedAt().atZone(ctx.lectures().zone()).format(Ui.DATE), "small",
                            b.awardedAt() == null ? "faint" : "success-text"));
            card.getStyleClass().add("card");
            if (b.awardedAt() == null) {
                card.setOpacity(0.55);
            } else {
                card.getStyleClass().add("stat-amber");
            }
            card.setPrefWidth(220);
            badges.getChildren().add(card);
        }

        VBox recent = new VBox(6);
        if (p.recent().isEmpty()) {
            recent.getChildren().add(Ui.label("No activity yet.", "faint"));
        }
        for (ProgressDao.ActivityRow a : p.recent()) {
            HBox row = Ui.row(10, Ui.label(a.label(), "small"), Ui.spacer(),
                    Ui.label(a.at().atZone(ctx.lectures().zone()).format(Ui.DATE_TIME), "small", "faint"));
            row.getStyleClass().add("list-row");
            recent.getChildren().add(row);
        }
        VBox recentCard = Ui.card("Recent activity", recent);
        HBox.setHgrow(recentCard, Priority.ALWAYS);
        return new VBox(20, Ui.pageHeader("Performance", "Your official results, attendance and learning habits."),
                stats, new HBox(20, scoresCard, weekCard), Ui.card("Badges", badges),
                new HBox(20, recentCard, QuizzesPage.weakTopicsCard(p.weakTopics())));
    }

    private Node scoreChart(Performance p) {
        LineChart<String, Number> chart = new LineChart<>(new CategoryAxis(), new NumberAxis(0, 100, 20));
        chart.setAnimated(false);
        chart.setLegendVisible(false);
        chart.setPrefHeight(240);
        XYChart.Series<String, Number> s = new XYChart.Series<>();
        int i = 1;
        for (ProgressDao.ScoreRow r : p.scores()) {
            s.getData().add(new XYChart.Data<>(i++ + ". " + r.courseCode(), Math.round(100.0 * r.score() / r.maxScore())));
        }
        chart.getData().add(s);
        return chart;
    }

    private Node weekChart(Performance p) {
        BarChart<String, Number> chart = new BarChart<>(new CategoryAxis(), new NumberAxis());
        chart.setAnimated(false);
        chart.setLegendVisible(false);
        chart.setPrefHeight(240);
        XYChart.Series<String, Number> s = new XYChart.Series<>();
        DateTimeFormatter f = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);
        p.weekly().forEach((monday, n) -> s.getData().add(new XYChart.Data<>(monday.format(f), n)));
        chart.getData().add(s);
        return chart;
    }
}
