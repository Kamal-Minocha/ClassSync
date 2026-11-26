package com.classsync.view;

import com.classsync.AppContext;
import com.classsync.model.Announcement;
import com.classsync.model.AnnouncementCategory;
import com.classsync.model.Course;
import com.classsync.model.CoursePermission;
import com.classsync.model.User;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;

/** Course announcements, newest first; publishing for the professor and delegated CRs. */
public class AnnouncementsPage extends Page {

    public AnnouncementsPage(AppContext ctx, AppShell shell) {
        super(ctx, shell);
    }

    @Override
    public Nav nav() {
        return Nav.ANNOUNCEMENTS;
    }

    @Override
    public Node build() {
        User u = user();
        VBox list = new VBox(12, Ui.loading("Loading announcements…"));
        Button create = Ui.primary("New announcement", Icons.MEGAPHONE);
        create.setVisible(false);
        create.setManaged(false);
        record Data(List<Announcement> items, List<Course> canPublish) { }
        load(() -> {
            List<Course> allowed = new ArrayList<>();
            for (Course c : ctx.courses().myCourses(u, false)) {
                if (ctx.courses().myPermissions(u, c.id()).contains(CoursePermission.PUBLISH_ANNOUNCEMENTS)) {
                    allowed.add(c);
                }
            }
            return new Data(ctx.announcements().list(u, null, 100), allowed);
        }, d -> {
            list.getChildren().clear();
            if (d.items().isEmpty()) {
                list.getChildren().add(Ui.emptyState(Icons.MEGAPHONE, "No announcements yet", null, null));
            }
            for (Announcement a : d.items()) {
                list.getChildren().add(card(a));
            }
            create.setVisible(!d.canPublish().isEmpty());
            create.setManaged(!d.canPublish().isEmpty());
            create.setOnAction(e -> CollabForms.announcement(ctx, shell, d.canPublish(), shell::refresh));
        });
        return Ui.page(new VBox(16, Ui.pageHeader("Announcements", "Notices from your courses", create), list));
    }

    static Label categoryBadge(AnnouncementCategory c) {
        return Ui.badge(c.label(), switch (c) {
            case URGENT -> "danger";
            case CANCELLATION, ATTENDANCE_WARNING -> "warning";
            case ROOM_CHANGE, QUIZ -> "info";
            default -> "neutral";
        });
    }

    VBox card(Announcement a) {
        VBox card = Ui.card(Ui.row(6, Ui.badge(a.courseCode(), "primary"), categoryBadge(a.category()),
                        a.lectureNo() == null ? Ui.label("") : Ui.badge("L" + a.lectureNo(), "neutral")),
                Ui.wrapping(a.title(), "h3"));
        if (!a.body().isBlank()) {
            card.getChildren().add(Ui.wrapping(a.body()));
        }
        card.getChildren().add(Ui.label(a.authorName() + " · " + a.createdAt().atZone(ctx.lectures().zone()).format(Ui.DATE_TIME),
                "small", "faint"));
        return card;
    }
}
