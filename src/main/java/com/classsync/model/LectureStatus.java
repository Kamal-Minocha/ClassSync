package com.classsync.model;

import java.util.Set;

/**
 * Lecture life cycle.
 *   DRAFT      visible to the professor and CR staff only
 *   PUBLISHED  visible to enrolled students (upcoming-topic preview)
 *   COMPLETED  taught; counts for attendance once attendance is finalized
 *   CANCELLED  kept for history, never counts for attendance
 */
public enum LectureStatus {
    DRAFT, PUBLISHED, COMPLETED, CANCELLED;

    /** Allowed transitions; anything else is rejected by LectureService. */
    public Set<LectureStatus> next() {
        return switch (this) {
            case DRAFT -> Set.of(PUBLISHED, CANCELLED);
            case PUBLISHED -> Set.of(DRAFT, COMPLETED, CANCELLED);
            case COMPLETED -> Set.of();
            case CANCELLED -> Set.of(DRAFT);
        };
    }

    public boolean visibleToStudents() {
        return this != DRAFT;
    }

    public String label() {
        return name().charAt(0) + name().substring(1).toLowerCase();
    }
}
