package com.classsync.model;

import java.time.Instant;

/**
 * Quiz settings plus derived totals. totalMarks is always SUM(questions.marks), never stored;
 * submissions = finished OFFICIAL attempts.
 * Contains no answer keys, so it is safe to show to students.
 */
public record Quiz(long id, long courseId, String courseCode, Long lectureId, Integer lectureNo, String title,
                   String instructions, Instant opensAt, Instant closesAt, int durationMinutes, QuizStatus status,
                   ReviewPolicy reviewPolicy, Instant resultsReleasedAt, boolean allowPractice,
                   int questionCount, int totalMarks, int submissions) {

    public boolean isOpenAt(Instant now) {
        return status == QuizStatus.PUBLISHED && !now.isBefore(opensAt) && now.isBefore(closesAt);
    }
}
