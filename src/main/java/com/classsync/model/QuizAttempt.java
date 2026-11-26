package com.classsync.model;

import java.time.Instant;

/** One attempt. score/maxScore are null while IN_PROGRESS. */
public record QuizAttempt(long id, long quizId, long studentId, int attemptNo, AttemptMode mode, AttemptStatus status,
                          Instant startedAt, Instant deadlineAt, Instant submittedAt, Integer score, Integer maxScore) {

    public double fraction() {
        return score == null || maxScore == null || maxScore == 0 ? 0 : (double) score / maxScore;
    }
}
