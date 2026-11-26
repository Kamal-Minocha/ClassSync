package com.classsync.model;

import java.util.List;

/** Editor view of a question, WITH the answer key. Never returned to students. */
public record Question(long id, long quizId, int position, QuestionKind kind, String prompt, int marks,
                       Long topicId, String topicName, String explanation, List<QuestionOption> options) {

    public QuestionOption correctOption() {
        return options.stream().filter(QuestionOption::correct).findFirst().orElse(null);
    }
}
