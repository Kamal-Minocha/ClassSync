package com.classsync.model;

import java.time.Instant;

/** One enrolled student's attendance for one lecture. status is null when nothing is recorded yet. */
public record AttendanceRecord(long studentId, String studentName, String enrollmentNo, AttendanceStatus status,
                               String source, Instant markedAt) {
}
