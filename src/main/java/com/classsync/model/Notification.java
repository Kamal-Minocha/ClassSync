package com.classsync.model;

import java.time.Instant;

/** An in-app notification. linkType/linkId point at the record to open (may be null). */
public record Notification(long id, String kind, String title, String body, String linkType, Long linkId,
                           Instant readAt, Instant createdAt) {

    public boolean unread() {
        return readAt == null;
    }
}
