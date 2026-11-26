package com.classsync.model;

/** DRAFT quizzes are visible only to the course professor; PUBLISHED ones to enrolled students. */
public enum QuizStatus {
    DRAFT, PUBLISHED;

    public String label() {
        return this == DRAFT ? "Draft" : "Published";
    }
}
