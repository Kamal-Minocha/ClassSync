package com.classsync.service;

import com.classsync.config.Database;
import com.classsync.config.Tx;
import com.classsync.dao.AttemptDao;
import com.classsync.dao.QuizDao;
import com.classsync.model.AttemptStatus;
import com.classsync.model.Quiz;
import com.classsync.model.User;
import com.classsync.service.AppException.AccessDeniedException;
import com.classsync.service.AppException.NotFoundException;
import com.classsync.service.QuizRules.Verdict;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * Quiz analytics for professors and the Weak Topic Detector for students and professors.
 *
 * Definitions (also in docs/VIVA_GUIDE.md):
 *   - Only finished OFFICIAL attempts count. Practice attempts never appear in academic analytics.
 *   - Participation = finished official attempts by currently enrolled students / currently enrolled
 *     students. No enrolled students → no percentage is shown.
 *   - Average / highest / lowest are over finished official attempts; none yet → shown as "—".
 *   - Question accuracy = correct answers / finished official attempts (unanswered counts as incorrect).
 */
public class QuizAnalyticsService {

    public record QuestionStat(long questionId, int position, String prompt, int marks, String topic, int answered,
                               int correct, int finished, String commonWrong, int commonWrongCount) {

        public OptionalDouble accuracy() {
            return finished == 0 ? OptionalDouble.empty() : OptionalDouble.of((double) correct / finished);
        }
    }

    public record QuizAnalytics(Quiz quiz, int enrolled, int participants, int finished, int inProgress,
                                Double averageScore, Integer highest, Integer lowest,
                                List<QuestionStat> questions, List<AttemptDao.SubmissionRow> submissions) {

        public OptionalDouble participation() {
            return enrolled == 0 ? OptionalDouble.empty() : OptionalDouble.of((double) participants / enrolled);
        }
    }

    public record TopicInsight(String courseCode, long topicId, String topic, int seen, int correct, Verdict verdict) {

        public OptionalDouble accuracy() {
            return seen == 0 ? OptionalDouble.empty() : OptionalDouble.of((double) correct / seen);
        }
    }

    /** Dashboard line for a professor: a quiz with its participation and average. */
    public record QuizSummary(Quiz quiz, int enrolled, int finished, OptionalDouble averageFraction) {
    }

    private final Database db;
    private final AccessPolicy access;
    private final QuizService quizService;
    private final QuizDao quizzes = new QuizDao();
    private final AttemptDao attempts = new AttemptDao();

    public QuizAnalyticsService(Database db, AccessPolicy access, QuizService quizService) {
        this.db = db;
        this.access = access;
        this.quizService = quizService;
    }

    /** Full analytics for one quiz (course professor only). Expired attempts are finalised first. */
    public QuizAnalytics analytics(User u, long quizId) throws SQLException {
        return Tx.inTransaction(db, con -> {
            Quiz q = quizzes.findById(con, quizId).orElseThrow(() -> new NotFoundException("Quiz not found."));
            access.requireCourseProfessor(con, u, q.courseId());
            quizService.finalizeExpired(con, null, quizId);
            q = quizzes.findById(con, quizId).orElseThrow();

            List<AttemptDao.SubmissionRow> subs = attempts.officialAttempts(con, quizId, q.courseId());
            List<AttemptDao.SubmissionRow> finished = subs.stream().filter(s -> s.status().finished()).toList();
            int participants = (int) finished.stream().filter(AttemptDao.SubmissionRow::enrolled).count();
            int inProgress = (int) subs.stream().filter(s -> s.status() == AttemptStatus.IN_PROGRESS).count();
            Double avg = finished.isEmpty() ? null : finished.stream().mapToInt(AttemptDao.SubmissionRow::score).average().orElse(0);
            Integer hi = finished.stream().map(AttemptDao.SubmissionRow::score).max(Integer::compare).orElse(null);
            Integer lo = finished.stream().map(AttemptDao.SubmissionRow::score).min(Integer::compare).orElse(null);

            List<QuestionStat> stats = new ArrayList<>();
            for (AttemptDao.QuestionStatRow r : attempts.questionStats(con, quizId)) {
                stats.add(new QuestionStat(r.questionId(), r.position(), r.prompt(), r.marks(), r.topic(), r.answered(),
                        r.correct(), finished.size(), r.commonWrong(), r.commonWrongCount()));
            }
            return new QuizAnalytics(q, attempts.enrolledCount(con, q.courseId()), participants, finished.size(),
                    inProgress, avg, hi, lo, stats, subs);
        });
    }

    /** Recent quizzes in the professor's courses with participation and average (dashboard). */
    public List<QuizSummary> recentSummaries(User u, int limit) throws SQLException {
        List<Quiz> list = quizService.list(u, null, null).stream().limit(limit).toList();
        try (Connection con = db.open()) {
            Map<Long, double[]> avgs = attempts.officialAverages(con, list.stream().map(Quiz::id).toList());
            Map<Long, Integer> enrolled = new LinkedHashMap<>();
            List<QuizSummary> out = new ArrayList<>();
            for (Quiz q : list) {
                Integer e = enrolled.get(q.courseId());
                if (e == null) {
                    e = attempts.enrolledCount(con, q.courseId());
                    enrolled.put(q.courseId(), e);
                }
                double[] a = avgs.get(q.id());
                out.add(new QuizSummary(q, e, a == null ? 0 : (int) a[0],
                        a == null ? OptionalDouble.empty() : OptionalDouble.of(a[1])));
            }
            return out;
        }
    }

    /**
     * The signed-in student's weak topics across their courses, using only released official results.
     * Sorted: weak topics first (lowest accuracy first), then on-track, then insufficient data.
     */
    public List<TopicInsight> weakTopicsForStudent(User u) throws SQLException {
        if (!u.role().isLearner()) {
            throw new AccessDeniedException("Weak topics are calculated for students.");
        }
        try (Connection con = db.open()) {
            Instant now = attempts.dbNow(con);
            List<AttemptDao.TopicRow> rows = attempts.topicRows(con, access.visibleCourseIds(con, u), u.id()).stream()
                    // never reveal correctness of answers whose results are not released yet
                    .filter(r -> QuizRules.resultsReleased(r.policy(), r.closesAt(), r.releasedAt(), true, now))
                    .toList();
            return aggregate(rows, QuizRules.MIN_STUDENT_SAMPLE);
        }
    }

    /** Class-wide weak topics for one course (course professor only). */
    public List<TopicInsight> weakTopicsForCourse(User u, long courseId) throws SQLException {
        try (Connection con = db.open()) {
            access.requireCourseProfessor(con, u, courseId);
            return aggregate(attempts.topicRows(con, List.of(courseId), null), QuizRules.MIN_CLASS_SAMPLE);
        }
    }

    static List<TopicInsight> aggregate(List<AttemptDao.TopicRow> rows, int minSample) {
        Map<Long, int[]> totals = new LinkedHashMap<>();
        Map<Long, AttemptDao.TopicRow> names = new LinkedHashMap<>();
        for (AttemptDao.TopicRow r : rows) {
            int[] t = totals.computeIfAbsent(r.topicId(), k -> new int[2]);
            t[0] += r.seen();
            t[1] += r.correct();
            names.putIfAbsent(r.topicId(), r);
        }
        List<TopicInsight> out = new ArrayList<>();
        totals.forEach((id, t) -> {
            AttemptDao.TopicRow n = names.get(id);
            out.add(new TopicInsight(n.courseCode(), id, n.topic(), t[0], t[1], QuizRules.classify(t[0], t[1], minSample)));
        });
        out.sort(Comparator.comparing((TopicInsight i) -> i.verdict().ordinal())
                .thenComparingDouble(i -> i.accuracy().orElse(1))
                .thenComparing(TopicInsight::topic));
        return out;
    }
}
