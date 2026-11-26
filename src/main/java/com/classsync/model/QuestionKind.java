package com.classsync.model;

public enum QuestionKind {
    MCQ("Multiple choice"), TRUE_FALSE("True / False");

    private final String label;

    QuestionKind(String label) {
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
