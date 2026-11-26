package com.classsync.model;

/** EXPIRED = the deadline passed and the attempt was submitted automatically with its saved answers. */
public enum AttemptStatus {
    IN_PROGRESS, SUBMITTED, EXPIRED;

    public boolean finished() {
        return this != IN_PROGRESS;
    }

    public String label() {
        return switch (this) {
            case IN_PROGRESS -> "In progress";
            case SUBMITTED -> "Submitted";
            case EXPIRED -> "Auto-submitted at deadline";
        };
    }
}
