package com.classsync.model;

import java.time.Instant;

/** An attendance session. It never carries the code itself (only its hash is stored). */
public record AttendanceSession(long id, long lectureId, long courseId, String courseCode, int lectureNo, String topic,
                                boolean open, Instant codeExpiresAt, Instant startedAt, Instant closedAt) {
}
