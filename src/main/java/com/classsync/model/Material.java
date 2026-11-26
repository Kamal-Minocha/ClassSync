package com.classsync.model;

import java.time.Instant;

/** Material / note metadata. The file itself lives under the uploads root at storedPath. */
public record Material(long id, long courseId, String courseCode, Long lectureId, Integer lectureNo,
                       MaterialKind kind, String title, String description, Integer unitNo,
                       long uploaderId, String uploaderName, String originalFilename, String storedPath,
                       String fileType, long sizeBytes, boolean published, ReviewStatus reviewStatus,
                       boolean recommended, int downloadCount, Instant archivedAt, Instant createdAt,
                       int votes) {

    public String sizeText() {
        if (sizeBytes < 1024) {
            return sizeBytes + " B";
        }
        if (sizeBytes < 1024 * 1024) {
            return (sizeBytes / 1024) + " KB";
        }
        return String.format("%.1f MB", sizeBytes / (1024.0 * 1024.0));
    }
}
