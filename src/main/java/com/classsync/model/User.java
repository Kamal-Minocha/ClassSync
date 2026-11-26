package com.classsync.model;

import java.time.Instant;

/** A signed-in or listed account. Never carries the password hash. */
public record User(long id, String email, String fullName, Role role, boolean active,
                   String theme, Instant createdAt, Instant lastLoginAt) {

    /** First given name, skipping an honorific such as "Dr." ("Dr. Meera Iyer" -> "Meera"). */
    public String firstName() {
        return nameParts()[0];
    }

    public String initials() {
        String[] parts = nameParts();
        String first = parts[0].substring(0, 1);
        String last = parts.length > 1 ? parts[parts.length - 1].substring(0, 1) : "";
        return (first + last).toUpperCase();
    }

    private String[] nameParts() {
        String[] parts = fullName.trim().split("\\s+");
        int start = 0;
        while (start < parts.length - 1
                && parts[start].replace(".", "").toLowerCase().matches("dr|prof|mr|mrs|ms")) {
            start++;
        }
        return java.util.Arrays.copyOfRange(parts, start, parts.length);
    }
}
