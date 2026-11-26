package com.classsync.view;

import com.classsync.AppContext;
import com.classsync.model.Lecture;
import com.classsync.model.User;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.VBox;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Weekly timetable plus previous / upcoming lecture lists. */
public class SchedulePage extends Page {

    private LocalDate weekStart;
    private final VBox weekBody = new VBox(14);
    private final Label weekLabel = Ui.label("", "h3");

    public SchedulePage(AppContext ctx, AppShell shell) {
        super(ctx, shell);
    }

    @Override
    public Nav nav() {
        return Nav.SCHEDULE;
    }

    @Override
    public Node build() {
        weekStart = LocalDate.now(ctx.clock()).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        Button prev = Ui.iconButton(Icons.CHEVRON_LEFT, "Previous week");
        prev.setOnAction(e -> { weekStart = weekStart.minusWeeks(1); loadWeek(); });
        Button next = Ui.iconButton(Icons.CHEVRON_RIGHT, "Next week");
        next.setOnAction(e -> { weekStart = weekStart.plusWeeks(1); loadWeek(); });
        Button today = Ui.button("This week", null);
        today.setOnAction(e -> {
            weekStart = LocalDate.now(ctx.clock()).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            loadWeek();
        });
        VBox week = new VBox(14, Ui.row(8, prev, next, today, weekLabel), weekBody);

        TabPane tabs = new TabPane(new Tab("Week", Ui.tabScroll(week)), new Tab("Upcoming", Ui.tabScroll(listTab(true))),
                new Tab("Previous", Ui.tabScroll(listTab(false))));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        loadWeek();
        return Ui.pageFixed(Ui.pageHeader("Lecture Schedule", "All lectures in your courses, in "
                + ctx.lectures().zone().getId() + " time"), tabs);
    }

    private void loadWeek() {
        User u = user();
        ZoneId zone = ctx.lectures().zone();
        LocalDate end = weekStart.plusDays(7);
        DateTimeFormatter f = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);
        weekLabel.setText(weekStart.format(f) + " – " + end.minusDays(1).format(f) + " " + end.getYear());
        weekBody.getChildren().setAll(Ui.loading("Loading timetable…"));
        load(() -> ctx.lectures().schedule(u, weekStart.atStartOfDay(zone).toInstant(), end.atStartOfDay(zone).toInstant()),
                lectures -> {
                    weekBody.getChildren().clear();
                    if (lectures.isEmpty()) {
                        weekBody.getChildren().add(Ui.emptyState(Icons.CALENDAR, "No lectures this week", null, null));
                        return;
                    }
                    Map<LocalDate, VBox> days = new TreeMap<>();
                    for (Lecture l : lectures) {
                        LocalDate d = l.startsAt().atZone(zone).toLocalDate();
                        days.computeIfAbsent(d, k -> new VBox(8)).getChildren()
                                .add(Widgets.lectureRow(l, zone, () -> shell.open(new LectureDetailPage(ctx, shell, l.id()))));
                    }
                    LocalDate todayDate = LocalDate.now(ctx.clock());
                    days.forEach((d, rows) -> {
                        String title = d.format(Ui.DATE) + (d.equals(todayDate) ? "  ·  Today" : "");
                        VBox card = Ui.card(title, rows);
                        weekBody.getChildren().add(card);
                    });
                });
    }

    private Node listTab(boolean upcoming) {
        User u = user();
        VBox list = new VBox(8, Ui.loading("Loading…"));
        load(() -> upcoming ? ctx.lectures().upcoming(u, 50) : ctx.lectures().previous(u, 50), (List<Lecture> lectures) -> {
            list.getChildren().clear();
            if (lectures.isEmpty()) {
                list.getChildren().add(Ui.emptyState(Icons.CALENDAR, upcoming ? "No upcoming lectures" : "No previous lectures", null, null));
            }
            for (Lecture l : lectures) {
                list.getChildren().add(Widgets.lectureRow(l, ctx.lectures().zone(),
                        () -> shell.open(new LectureDetailPage(ctx, shell, l.id()))));
            }
        });
        return list;
    }
}
