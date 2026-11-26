package com.classsync.model;

import java.time.Instant;

/** A lecture: the learning unit of ClassSync. */
public record Lecture(long id, long courseId, String courseCode, String courseName, int lectureNo, int unitNo,
                      Long topicId, String topic, String description, String objectives, String preparation,
                      String room, Instant startsAt, Instant endsAt, LectureStatus status,
                      boolean feedbackOpen, boolean feedbackAnonymous, Instant attendanceFinalizedAt) {

    public String title() {
        return "L" + lectureNo + " · " + topic;
    }

    public boolean attendanceFinalized() {
        return attendanceFinalizedAt != null;
    }
}
