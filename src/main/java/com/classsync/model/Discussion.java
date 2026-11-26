package com.classsync.model;

import java.time.Instant;

/** A doubt posted in a course (optionally about one lecture / syllabus topic). */
public record Discussion(long id, long courseId, String courseCode, Long lectureId, Integer lectureNo, Long topicId,
                         String topicName, long authorId, String authorName, String title, String body,
                         Instant resolvedAt, String resolvedByName, boolean hidden, Instant createdAt, int replyCount,
                         boolean professorAnswered) {

    public boolean resolved() {
        return resolvedAt != null;
    }
}
