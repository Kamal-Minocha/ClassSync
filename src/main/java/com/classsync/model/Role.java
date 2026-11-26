package com.classsync.model;

/**
 * Application roles. A CR is a student with course-scoped staff rights. An ADMIN only manages user accounts
 * (no courses, no teaching, no learning features).
 */
public enum Role {
    STUDENT("Student"),
    PROFESSOR("Professor"),
    CR("Class Representative"),
    ADMIN("Administrator");

    private final String label;

    Role(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** Students and CRs both have a students row and use learner features. */
    public boolean isLearner() {
        return this == STUDENT || this == CR;
    }
}
