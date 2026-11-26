package com.classsync.model;

public enum MaterialKind {
    MATERIAL("Lecture material"),
    NOTE("Student note"),
    PYQ("Previous-year questions"),
    REFERENCE("Reference");

    private final String label;

    MaterialKind(String label) {
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
