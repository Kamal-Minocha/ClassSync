package com.classsync.model;

/**
 * Course-scoped staff permissions. VIEW_SCHEDULE and UPLOAD_MATERIALS are the defaults every
 * CR staff member has; the others must be delegated explicitly by the course professor.
 */
public enum CoursePermission {
    VIEW_SCHEDULE("View course schedule incl. drafts", null),
    UPLOAD_MATERIALS("Upload course materials", null),
    UPDATE_LECTURES("Update lecture information", "can_update_lectures"),
    REVIEW_NOTES("Review student notes", "can_review_notes"),
    PUBLISH_ANNOUNCEMENTS("Publish announcements", "can_publish_announcements"),
    ORGANIZE_RESOURCES("Publish and organise resources", "can_organize_resources"),
    MODERATE_DISCUSSIONS("Moderate discussions", "can_moderate_discussions");

    private final String label;
    private final String column;

    CoursePermission(String label, String column) {
        this.label = label;
        this.column = column;
    }

    public String label() {
        return label;
    }

    /** course_staff column, or null for default rights. */
    public String column() {
        return column;
    }

    public boolean delegable() {
        return column != null;
    }
}
