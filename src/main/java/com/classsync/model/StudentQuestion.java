package com.classsync.model;

import java.util.List;

/**
 * What a student sees while attempting a quiz. Deliberately has NO correct-answer flag and NO
 * explanation: the DAO query that builds it does not even select those columns.
 */
public record StudentQuestion(long id, int position, QuestionKind kind, String prompt, int marks, List<Choice> choices) {

    public record Choice(long id, String text) {
    }
}
