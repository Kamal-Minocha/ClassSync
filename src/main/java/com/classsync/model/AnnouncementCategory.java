package com.classsync.model;

public enum AnnouncementCategory {
    GENERAL("General"), URGENT("Urgent"), CANCELLATION("Cancellation"), ROOM_CHANGE("Room change"),
    QUIZ("Quiz"), MATERIAL("Material update"), ATTENDANCE_WARNING("Attendance warning");

    private final String label;

    AnnouncementCategory(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    @Override
    public String toString() {
        return label;
    }
}
