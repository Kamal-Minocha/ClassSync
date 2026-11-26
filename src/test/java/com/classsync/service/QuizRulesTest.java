package com.classsync.service;

import com.classsync.model.ReviewPolicy;
import com.classsync.service.QuizRules.Verdict;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/** Pure rules: result release and the weak-topic thresholds. */
class QuizRulesTest {

    private static final Instant CLOSE = Instant.parse("2026-11-24T10:00:00Z");

    @Test
    void afterSubmitReleasesOnlyFinishedAttempts() {
        assertFalse(QuizRules.resultsReleased(ReviewPolicy.AFTER_SUBMIT, CLOSE, null, false, CLOSE.minusSeconds(60)));
        assertTrue(QuizRules.resultsReleased(ReviewPolicy.AFTER_SUBMIT, CLOSE, null, true, CLOSE.minusSeconds(60)));
    }

    @Test
    void afterCloseReleasesAtTheClosingInstant() {
        assertFalse(QuizRules.resultsReleased(ReviewPolicy.AFTER_CLOSE, CLOSE, null, true, CLOSE.minusMillis(1)));
        assertTrue(QuizRules.resultsReleased(ReviewPolicy.AFTER_CLOSE, CLOSE, null, true, CLOSE));
    }

    @Test
    void manualWaitsForTheProfessor() {
        assertFalse(QuizRules.resultsReleased(ReviewPolicy.MANUAL, CLOSE, null, true, CLOSE.plusSeconds(86_400)));
        assertTrue(QuizRules.resultsReleased(ReviewPolicy.MANUAL, CLOSE, CLOSE, true, CLOSE));
    }

    @Test
    void weakTopicThresholds() {
        assertEquals(Verdict.INSUFFICIENT_DATA, QuizRules.classify(0, 0, 3), "no data never divides by zero");
        assertEquals(Verdict.INSUFFICIENT_DATA, QuizRules.classify(2, 0, 3), "below the minimum sample");
        assertEquals(Verdict.WEAK, QuizRules.classify(3, 1, 3), "33% < 60%");
        assertEquals(Verdict.OK, QuizRules.classify(5, 3, 3), "exactly 60% is not weak");
        assertEquals(Verdict.WEAK, QuizRules.classify(10, 5, 10));
        assertEquals(Verdict.INSUFFICIENT_DATA, QuizRules.classify(9, 0, QuizRules.MIN_CLASS_SAMPLE));
    }
}
