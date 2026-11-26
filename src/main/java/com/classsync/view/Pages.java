package com.classsync.view;

import com.classsync.AppContext;
import com.classsync.model.Role;

import java.util.List;

/**
 * Page registry: which sidebar entries each role sees and which Page class opens for each.
 * Entries are only listed once their module is implemented — no placeholder screens.
 */
public final class Pages {

    private Pages() {
    }

    public static List<Nav> navFor(Role role) {
        return switch (role) {
            case STUDENT, CR -> List.of(Nav.DASHBOARD, Nav.COURSES, Nav.SCHEDULE, Nav.MATERIALS, Nav.QUIZZES, Nav.ATTENDANCE, Nav.FEEDBACK,
                    Nav.NOTES, Nav.DISCUSSIONS, Nav.ANNOUNCEMENTS,
                    Nav.LIBRARY, Nav.REVISION, Nav.PERFORMANCE, Nav.BOOKMARKS, Nav.SETTINGS);
            case PROFESSOR -> List.of(Nav.DASHBOARD, Nav.COURSES, Nav.SCHEDULE, Nav.MATERIALS, Nav.QUIZZES, Nav.ATTENDANCE, Nav.FEEDBACK,
                    Nav.NOTES, Nav.DISCUSSIONS, Nav.ANNOUNCEMENTS,
                    Nav.LIBRARY, Nav.PLANNER, Nav.SETTINGS);
            case ADMIN -> List.of(Nav.DASHBOARD, Nav.USERS, Nav.SETTINGS);
        };
    }

    public static Page create(Nav nav, AppContext ctx, AppShell shell) {
        return switch (nav) {
            case DASHBOARD -> isAdmin(ctx) ? new AdminDashboardPage(ctx, shell) : new DashboardPage(ctx, shell);
            case USERS -> new UsersPage(ctx, shell);
            case COURSES -> new CoursesPage(ctx, shell);
            case SCHEDULE -> new SchedulePage(ctx, shell);
            case MATERIALS -> new MaterialsPage(ctx, shell);
            case QUIZZES -> new QuizzesPage(ctx, shell);
            case ATTENDANCE -> new AttendancePage(ctx, shell);
            case FEEDBACK -> new FeedbackPage(ctx, shell);
            case NOTES -> new NotesPage(ctx, shell);
            case DISCUSSIONS -> new DiscussionsPage(ctx, shell);
            case ANNOUNCEMENTS -> new AnnouncementsPage(ctx, shell);
            case LIBRARY -> new LibraryPage(ctx, shell);
            case REVISION -> new RevisionPage(ctx, shell);
            case PERFORMANCE -> new PerformancePage(ctx, shell);
            case PLANNER -> new PlannerPage(ctx, shell);
            case BOOKMARKS -> new BookmarksPage(ctx, shell);
            case SETTINGS -> new SettingsPage(ctx, shell);
            default -> null;
        };
    }

    private static boolean isAdmin(AppContext ctx) {
        var u = ctx.session().user();
        return u != null && u.role() == Role.ADMIN;
    }
}
