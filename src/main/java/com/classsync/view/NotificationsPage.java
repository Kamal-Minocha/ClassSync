package com.classsync.view;

import com.classsync.AppContext;
import com.classsync.model.Notification;
import com.classsync.model.User;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/** The signed-in user's notifications. Clicking one marks it read and opens the record it refers to. */
public class NotificationsPage extends Page {

    public NotificationsPage(AppContext ctx, AppShell shell) {
        super(ctx, shell);
    }

    @Override
    public Nav nav() {
        return Nav.NONE;
    }

    @Override
    public Node build() {
        User u = user();
        VBox list = new VBox(8, Ui.loading("Loading notifications…"));
        CheckBox unreadOnly = new CheckBox("Unread only");
        Button readAll = Ui.button("Mark all as read", Icons.CHECK);
        Runnable[] reload = new Runnable[1];
        reload[0] = () -> load(() -> ctx.notifications().list(u, unreadOnly.isSelected(), 100), items -> {
            list.getChildren().clear();
            if (items.isEmpty()) {
                list.getChildren().add(Ui.emptyState(Icons.BELL, "You're all caught up",
                        "Announcements, replies to your doubts, note reviews and other updates appear here.", null));
            }
            for (Notification n : items) {
                list.getChildren().add(row(u, n));
            }
        });
        unreadOnly.setOnAction(e -> reload[0].run());
        readAll.setOnAction(e -> load(() -> ctx.notifications().markAllRead(u), n -> shell.refresh()));
        reload[0].run();
        return Ui.page(new VBox(16, Ui.pageHeader("Notifications", "Updates from your courses", unreadOnly, readAll), list));
    }

    private Node row(User u, Notification n) {
        VBox text = new VBox(3, Ui.wrapping(n.title(), n.unread() ? "body-strong" : "muted"));
        if (!n.body().isBlank()) {
            text.getChildren().add(Ui.wrapping(n.body(), "small", "muted"));
        }
        text.getChildren().add(Ui.label(n.createdAt().atZone(ctx.lectures().zone()).format(Ui.DATE_TIME), "small", "faint"));
        HBox.setHgrow(text, Priority.ALWAYS);
        text.setMinWidth(0);
        HBox row = new HBox(12, Icons.of(icon(n.kind()), 20), text);
        if (n.unread()) {
            row.getChildren().add(Ui.badge("New", "primary"));
        }
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().addAll("list-row", "list-row-clickable");
        Ui.clickable(row, () -> ctx.background().run(() -> {
            ctx.notifications().markRead(u, n.id());
            return null;
        }, r -> open(n), shell::showError));
        row.getStyleClass().remove("card-clickable");
        return row;
    }

    private static String icon(String kind) {
        return switch (kind) {
            case "ANNOUNCEMENT" -> Icons.MEGAPHONE;
            case "LECTURE" -> Icons.CALENDAR;
            case "QUIZ" -> Icons.QUIZ;
            case "MATERIAL" -> Icons.FILE;
            case "NOTE" -> Icons.NOTE;
            case "DISCUSSION" -> Icons.CHAT;
            case "ATTENDANCE" -> Icons.ATTENDANCE;
            default -> Icons.BELL;
        };
    }

    /** Opens the linked record; access is re-checked by the page's own service calls. */
    private void open(Notification n) {
        if (n.linkType() == null || n.linkId() == null) {
            shell.refresh();
            return;
        }
        switch (n.linkType()) {
            case "LECTURE" -> shell.open(new LectureDetailPage(ctx, shell, n.linkId()));
            case "QUIZ" -> shell.open(Nav.QUIZZES);
            case "MATERIAL" -> shell.open(Nav.MATERIALS);
            case "NOTE" -> shell.open(Nav.NOTES);
            case "DISCUSSION" -> shell.open(new DiscussionThreadPage(ctx, shell, n.linkId()));
            case "ANNOUNCEMENT" -> shell.open(Nav.ANNOUNCEMENTS);
            case "ATTENDANCE" -> shell.open(Nav.ATTENDANCE);
            default -> shell.refresh();
        }
    }
}
