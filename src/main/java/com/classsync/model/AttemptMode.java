package com.classsync.model;

/** OFFICIAL attempts count toward academic results; PRACTICE (revision) attempts never do. */
public enum AttemptMode {
    OFFICIAL, PRACTICE;

    public String label() {
        return this == OFFICIAL ? "Official" : "Practice";
    }
}
