package com.classsync.model;

/** When students may see their score, the correct answers and explanations. */
public enum ReviewPolicy {
    AFTER_SUBMIT("Right after the student submits"),
    AFTER_CLOSE("After the quiz closes"),
    MANUAL("When the professor releases results");

    private final String label;

    ReviewPolicy(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    @Override
    public String toString() {
        return label;
    }
}
