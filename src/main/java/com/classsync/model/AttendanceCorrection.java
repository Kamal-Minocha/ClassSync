package com.classsync.model;

import java.time.Instant;

public record AttendanceCorrection(long id, long lectureId, long studentId, String studentName,
                                   AttendanceStatus oldStatus, AttendanceStatus newStatus, String reason,
                                   String correctedByName, Instant correctedAt) {
}
