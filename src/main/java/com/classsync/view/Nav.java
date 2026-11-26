package com.classsync.view;

/** Sidebar destinations. Which ones a user sees depends on role and implemented modules (see Pages). */
public enum Nav {
    DASHBOARD("Dashboard", Icons.DASHBOARD),
    COURSES("My Courses", Icons.COURSES),
    SCHEDULE("Lecture Schedule", Icons.CALENDAR),
    MATERIALS("Study Materials", Icons.FILE),
    QUIZZES("Quizzes", Icons.QUIZ),
    ATTENDANCE("Attendance", Icons.ATTENDANCE),
    FEEDBACK("Feedback", Icons.STAR),
    NOTES("Notes", Icons.NOTE),
    DISCUSSIONS("Discussions", Icons.CHAT),
    ANNOUNCEMENTS("Announcements", Icons.MEGAPHONE),
    LIBRARY("Resource Library", Icons.LIBRARY),
    REVISION("Revision", Icons.REVISION),
    PERFORMANCE("Performance", Icons.CHART),
    PLANNER("Semester Planner", Icons.PLANNER),
    BOOKMARKS("Bookmarks & Tasks", Icons.BOOKMARK),
    USERS("User Management", Icons.USERS),
    SETTINGS("Settings", Icons.SETTINGS),
    NONE("", Icons.DASHBOARD);

    private final String label;
    private final String icon;

    Nav(String label, String icon) {
        this.label = label;
        this.icon = icon;
    }

    public String label() {
        return label;
    }

    public String icon() {
        return icon;
    }
}
