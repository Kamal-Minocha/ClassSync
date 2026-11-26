package com.classsync.model;

import java.time.Instant;

public record Announcement(long id, long courseId, String courseCode, Long lectureId, Integer lectureNo, long authorId,
                           String authorName, AnnouncementCategory category, String title, String body, Instant createdAt) {
}
