package com.classsync.model;

public enum AttendanceStatus {
    PRESENT, ABSENT;

    public String label() {
        return this == PRESENT ? "Present" : "Absent";
    }

    @Override
    public String toString() {
        return label();
    }
}
