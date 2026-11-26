package com.classsync.model;

import java.time.Instant;

/** A course with its assigned professor; enrolledCount is filled by list queries. */
public record Course(long id, String code, String name, String department, int semester, String description,
                     long professorId, String professorName, Instant archivedAt, int enrolledCount) {

    public boolean archived() {
        return archivedAt != null;
    }

    public String display() {
        return code + " · " + name;
    }
}
