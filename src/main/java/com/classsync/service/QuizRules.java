package com.classsync.service;

import com.classsync.model.ReviewPolicy;

import java.time.Instant;

/**
 * Pure quiz rules with no database access, so they can be unit-tested directly.
 *
 * <h2>Result release</h2>
 * A student may see their score, the correct answers and explanations only when:
 * <ul>
 *   <li>AFTER_SUBMIT — their attempt is finished;</li>
 *   <li>AFTER_CLOSE — the quiz closing time has passed;</li>
 *   <li>MANUAL — the professor has pressed "Release results".</li>
 * </ul>
 * "now" is always database time supplied by the caller.
 *
 * <h2>Weak Topic Detector</h2>
 * Plain counting, no AI or machine learning. For each syllabus topic we count the questions on that
 * topic a student "saw" in finished OFFICIAL attempts whose results are released (unanswered = seen
 * and incorrect) and how many were correct. Practice attempts and questions without a topic are ignored.
 * <ul>
 *   <li>fewer than the minimum sample → INSUFFICIENT_DATA (no verdict is shown, never a percentage of nothing);</li>
 *   <li>accuracy below {@link #WEAK_THRESHOLD} → WEAK;</li>
 *   <li>otherwise → OK.</li>
 * </ul>
 * Minimum samples: {@link #MIN_STUDENT_SAMPLE} questions for one student,
 * {@link #MIN_CLASS_SAMPLE} answers for a whole class.
 */
public final class QuizRules {

    /** Accuracy strictly below 60% marks a topic as weak. */
    public static final double WEAK_THRESHOLD = 0.60;
    public static final int MIN_STUDENT_SAMPLE = 3;
    public static final int MIN_CLASS_SAMPLE = 10;

    public enum Verdict {
        WEAK("Needs revision"), OK("On track"), INSUFFICIENT_DATA("Not enough data yet");

        private final String label;

        Verdict(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private QuizRules() {
    }

    public static boolean resultsReleased(ReviewPolicy policy, Instant closesAt, Instant releasedAt,
                                          boolean attemptFinished, Instant now) {
        return switch (policy) {
            case AFTER_SUBMIT -> attemptFinished;
            case AFTER_CLOSE -> !now.isBefore(closesAt);
            case MANUAL -> releasedAt != null;
        };
    }

    public static Verdict classify(int seen, int correct, int minSample) {
        if (seen < minSample || seen <= 0) {
            return Verdict.INSUFFICIENT_DATA;
        }
        return (double) correct / seen < WEAK_THRESHOLD ? Verdict.WEAK : Verdict.OK;
    }
}
