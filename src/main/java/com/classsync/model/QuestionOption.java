package com.classsync.model;

/** A full option including the answer key. Only the course professor (editor) ever receives this type. */
public record QuestionOption(long id, int position, String text, boolean correct) {
}
