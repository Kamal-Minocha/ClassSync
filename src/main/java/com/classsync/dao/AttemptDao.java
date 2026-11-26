package com.classsync.dao;

import com.classsync.model.AttemptMode;
import com.classsync.model.AttemptStatus;
import com.classsync.model.QuizAttempt;
import com.classsync.model.ReviewPolicy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Quiz attempts, saved answers, scoring and the aggregate queries used by analytics.
 *
 * Time is always taken from PostgreSQL (now() / clock_timestamp()), never from the desktop clock,
 * so a student cannot gain time by changing the computer's clock.
 */
public class AttemptDao {

    /** One graded answer row for review. chosenOptionId is null when the question was left unanswered. */
    public record AnswerRow(long questionId, Long chosenOptionId, Boolean correct) {
    }

    /** A finished official attempt in the submissions list. */
    public record SubmissionRow(long attemptId, long studentId, String studentName, String enrollmentNo,
                                AttemptStatus status, Integer score, Integer maxScore, Instant startedAt,
                                Instant submittedAt, boolean enrolled) {
    }

    /** Per-question aggregate over finished OFFICIAL attempts. */
    public record QuestionStatRow(long questionId, int position, String prompt, int marks, String topic,
                                  int answered, int correct, String commonWrong, int commonWrongCount) {
    }

    /** Topic aggregate for one quiz (the release check is applied in Java per quiz). */
    public record TopicRow(long quizId, ReviewPolicy policy, Instant closesAt, Instant releasedAt, String courseCode,
                           long topicId, String topic, int seen, int correct) {
    }

    /** Current database time. clock_timestamp() is the real time even inside a long transaction. */
    public Instant dbNow(Connection con) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT clock_timestamp()");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getObject(1, OffsetDateTime.class).toInstant();
        }
    }

    /**
     * Starts an attempt. The deadline is computed by PostgreSQL:
     *   OFFICIAL: least(now + duration, closes_at), and only while opens_at <= now < closes_at
     *   PRACTICE: now + duration (eligibility is checked by the service)
     * Returns empty when an official attempt is requested outside the quiz window.
     * The partial unique indexes reject a second official or a second in-progress attempt (SQLSTATE 23505).
     */
    public Optional<Long> start(Connection con, long quizId, long studentId, AttemptMode mode) throws SQLException {
        String sql = """
                INSERT INTO quiz_attempts (quiz_id, student_id, attempt_no, mode, status, started_at, deadline_at)
                SELECT q.id, ?,
                       (SELECT coalesce(max(a.attempt_no), 0) + 1 FROM quiz_attempts a WHERE a.quiz_id = q.id AND a.student_id = ?),
                       ?, 'IN_PROGRESS', t.now,
                       CASE WHEN ? = 'OFFICIAL'
                            THEN least(t.now + make_interval(mins => q.duration_minutes), q.closes_at)
                            ELSE t.now + make_interval(mins => q.duration_minutes) END
                FROM quizzes q
                -- read the clock ONCE so start, deadline and the window check all use the same instant
                CROSS JOIN (SELECT clock_timestamp() AS now) t
                WHERE q.id = ? AND q.status = 'PUBLISHED'
                  AND (? = 'PRACTICE' OR (t.now >= q.opens_at AND t.now < q.closes_at))
                RETURNING id""";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, studentId);
            ps.setLong(2, studentId);
            ps.setString(3, mode.name());
            ps.setString(4, mode.name());
            ps.setLong(5, quizId);
            ps.setString(6, mode.name());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getLong(1)) : Optional.empty();
            }
        }
    }

    public Optional<QuizAttempt> findById(Connection con, long attemptId) throws SQLException {
        return one(con, "SELECT * FROM quiz_attempts WHERE id = ?", attemptId);
    }

    /** Row lock: submit, autosave and expiry handling of one attempt run one after another. */
    public Optional<QuizAttempt> lockById(Connection con, long attemptId) throws SQLException {
        return one(con, "SELECT * FROM quiz_attempts WHERE id = ? FOR UPDATE", attemptId);
    }

    public List<QuizAttempt> forStudent(Connection con, long quizId, long studentId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT * FROM quiz_attempts WHERE quiz_id = ? AND student_id = ? ORDER BY attempt_no")) {
            ps.setLong(1, quizId);
            ps.setLong(2, studentId);
            return map(ps);
        }
    }

    /** All of a student's attempts in the given quizzes (for list screens). */
    public List<QuizAttempt> forStudent(Connection con, List<Long> quizIds, long studentId) throws SQLException {
        if (quizIds.isEmpty()) {
            return List.of();
        }
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT * FROM quiz_attempts WHERE quiz_id = ANY(?) AND student_id = ? ORDER BY quiz_id, attempt_no")) {
            ps.setArray(1, con.createArrayOf("bigint", quizIds.toArray()));
            ps.setLong(2, studentId);
            return map(ps);
        }
    }

    /** In-progress attempts whose deadline has passed (for one student, or for one quiz). */
    public List<Long> expiredOpenAttempts(Connection con, Long studentId, Long quizId) throws SQLException {
        String sql = """
                SELECT id FROM quiz_attempts
                WHERE status = 'IN_PROGRESS' AND deadline_at <= clock_timestamp()
                  AND (?::bigint IS NULL OR student_id = ?::bigint)
                  AND (?::bigint IS NULL OR quiz_id = ?::bigint)""";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            MaterialDao.setNullableLong(ps, 1, studentId);
            MaterialDao.setNullableLong(ps, 2, studentId);
            MaterialDao.setNullableLong(ps, 3, quizId);
            MaterialDao.setNullableLong(ps, 4, quizId);
            List<Long> ids = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getLong(1));
                }
            }
            return ids;
        }
    }

    /** Insert or replace the chosen option for one question (autosave). */
    public void saveAnswer(Connection con, long attemptId, long quizId, long questionId, long optionId) throws SQLException {
        String sql = """
                INSERT INTO answers (attempt_id, quiz_id, question_id, option_id, saved_at)
                VALUES (?, ?, ?, ?, clock_timestamp())
                ON CONFLICT (attempt_id, question_id)
                DO UPDATE SET option_id = EXCLUDED.option_id, saved_at = EXCLUDED.saved_at""";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, attemptId);
            ps.setLong(2, quizId);
            ps.setLong(3, questionId);
            ps.setLong(4, optionId);
            ps.executeUpdate();
        }
    }

    /** questionId -> chosen optionId. */
    public Map<Long, Long> savedAnswers(Connection con, long attemptId) throws SQLException {
        Map<Long, Long> map = new HashMap<>();
        try (PreparedStatement ps = con.prepareStatement("SELECT question_id, option_id FROM answers WHERE attempt_id = ?")) {
            ps.setLong(1, attemptId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    map.put(rs.getLong(1), rs.getLong(2));
                }
            }
        }
        return map;
    }

    /**
     * Marks each saved answer correct/incorrect from the answer key and returns {score, maxScore}.
     * Unanswered questions score 0. maxScore = SUM(marks) of the quiz's questions.
     */
    public int[] grade(Connection con, long attemptId, long quizId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("""
                UPDATE answers a SET is_correct = o.is_correct
                FROM question_options o
                WHERE a.attempt_id = ? AND o.id = a.option_id""")) {
            ps.setLong(1, attemptId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = con.prepareStatement("""
                SELECT coalesce(sum(qu.marks) FILTER (WHERE a.is_correct), 0), coalesce(sum(qu.marks), 0)
                FROM questions qu
                LEFT JOIN answers a ON a.question_id = qu.id AND a.attempt_id = ?
                WHERE qu.quiz_id = ?""")) {
            ps.setLong(1, attemptId);
            ps.setLong(2, quizId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return new int[]{rs.getInt(1), rs.getInt(2)};
            }
        }
    }

    /** Conditional finish: only an IN_PROGRESS attempt changes, so a second submit can never score twice. */
    public boolean finish(Connection con, long attemptId, AttemptStatus status, int score, int maxScore) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("""
                UPDATE quiz_attempts SET status = ?, submitted_at = clock_timestamp(), score = ?, max_score = ?
                WHERE id = ? AND status = 'IN_PROGRESS'""")) {
            ps.setString(1, status.name());
            ps.setInt(2, score);
            ps.setInt(3, maxScore);
            ps.setLong(4, attemptId);
            return ps.executeUpdate() == 1;
        }
    }

    /** Graded answers of a finished attempt, one row per question (unanswered included). */
    public List<AnswerRow> answerRows(Connection con, long attemptId, long quizId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("""
                SELECT qu.id, a.option_id, a.is_correct
                FROM questions qu
                LEFT JOIN answers a ON a.question_id = qu.id AND a.attempt_id = ?
                WHERE qu.quiz_id = ?
                ORDER BY qu.position""")) {
            ps.setLong(1, attemptId);
            ps.setLong(2, quizId);
            List<AnswerRow> rows = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long option = rs.getLong(2);
                    Long chosen = rs.wasNull() ? null : option;
                    boolean correct = rs.getBoolean(3);
                    rows.add(new AnswerRow(rs.getLong(1), chosen, rs.wasNull() ? null : correct));
                }
            }
            return rows;
        }
    }

    // ------------------------------------------------------------------ analytics

    /** Official attempts of a quiz (finished and in progress), best first. */
    public List<SubmissionRow> officialAttempts(Connection con, long quizId, long courseId) throws SQLException {
        String sql = """
                SELECT a.id, a.student_id, u.full_name, s.enrollment_no, a.status, a.score, a.max_score,
                       a.started_at, a.submitted_at,
                       EXISTS (SELECT 1 FROM enrollments e WHERE e.course_id = ? AND e.student_id = a.student_id) AS enrolled
                FROM quiz_attempts a
                JOIN users u ON u.id = a.student_id
                LEFT JOIN students s ON s.user_id = a.student_id
                WHERE a.quiz_id = ? AND a.mode = 'OFFICIAL'
                ORDER BY a.score DESC NULLS LAST, a.submitted_at, u.full_name""";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, courseId);
            ps.setLong(2, quizId);
            List<SubmissionRow> rows = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int score = rs.getInt("score");
                    Integer s = rs.wasNull() ? null : score;
                    int max = rs.getInt("max_score");
                    Integer m = rs.wasNull() ? null : max;
                    OffsetDateTime submitted = rs.getObject("submitted_at", OffsetDateTime.class);
                    rows.add(new SubmissionRow(rs.getLong("id"), rs.getLong("student_id"), rs.getString("full_name"),
                            rs.getString("enrollment_no"), AttemptStatus.valueOf(rs.getString("status")), s, m,
                            rs.getObject("started_at", OffsetDateTime.class).toInstant(),
                            submitted == null ? null : submitted.toInstant(), rs.getBoolean("enrolled")));
                }
            }
            return rows;
        }
    }

    /**
     * Accuracy per question over finished OFFICIAL attempts, plus the most frequently chosen wrong option.
     * Practice attempts are excluded so revision does not distort academic analytics.
     */
    public List<QuestionStatRow> questionStats(Connection con, long quizId) throws SQLException {
        String sql = """
                WITH official AS (
                    SELECT id FROM quiz_attempts WHERE quiz_id = ? AND mode = 'OFFICIAL' AND status <> 'IN_PROGRESS'
                ), wrong AS (
                    SELECT DISTINCT ON (a.question_id) a.question_id, o.text, count(*) AS n
                    FROM answers a
                    JOIN official f ON f.id = a.attempt_id
                    JOIN question_options o ON o.id = a.option_id
                    WHERE a.is_correct = FALSE
                    GROUP BY a.question_id, o.id, o.text, o.position
                    ORDER BY a.question_id, n DESC, o.position
                )
                SELECT qu.id, qu.position, qu.prompt, qu.marks, t.name AS topic,
                       count(a.question_id) AS answered,
                       count(a.question_id) FILTER (WHERE a.is_correct) AS correct,
                       w.text AS common_wrong, coalesce(w.n, 0) AS common_wrong_count
                FROM questions qu
                LEFT JOIN topics t ON t.id = qu.topic_id
                LEFT JOIN answers a ON a.question_id = qu.id AND a.attempt_id IN (SELECT id FROM official)
                LEFT JOIN wrong w ON w.question_id = qu.id
                WHERE qu.quiz_id = ?
                GROUP BY qu.id, qu.position, qu.prompt, qu.marks, t.name, w.text, w.n
                ORDER BY qu.position""";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, quizId);
            ps.setLong(2, quizId);
            List<QuestionStatRow> rows = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new QuestionStatRow(rs.getLong("id"), rs.getInt("position"), rs.getString("prompt"),
                            rs.getInt("marks"), rs.getString("topic"), rs.getInt("answered"), rs.getInt("correct"),
                            rs.getString("common_wrong"), rs.getInt("common_wrong_count")));
                }
            }
            return rows;
        }
    }

    /**
     * Topic aggregates over finished OFFICIAL attempts, grouped per quiz so the service can apply the
     * result-release rule. studentId null = whole class. Unanswered questions count as seen and incorrect;
     * questions without a topic are ignored.
     */
    public List<TopicRow> topicRows(Connection con, List<Long> courseIds, Long studentId) throws SQLException {
        if (courseIds.isEmpty()) {
            return List.of();
        }
        String sql = """
                SELECT q.id AS quiz_id, q.review_policy, q.closes_at, q.results_released_at, c.code,
                       t.id AS topic_id, t.name AS topic,
                       count(*) AS seen, count(*) FILTER (WHERE a.is_correct) AS correct
                FROM quiz_attempts at
                JOIN quizzes q ON q.id = at.quiz_id
                JOIN courses c ON c.id = q.course_id
                JOIN questions qu ON qu.quiz_id = q.id
                JOIN topics t ON t.id = qu.topic_id
                LEFT JOIN answers a ON a.attempt_id = at.id AND a.question_id = qu.id
                WHERE at.mode = 'OFFICIAL' AND at.status <> 'IN_PROGRESS'
                  AND q.course_id = ANY(?)
                  AND (?::bigint IS NULL OR at.student_id = ?::bigint)
                GROUP BY q.id, c.code, t.id, t.name""";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setArray(1, con.createArrayOf("bigint", courseIds.toArray()));
            MaterialDao.setNullableLong(ps, 2, studentId);
            MaterialDao.setNullableLong(ps, 3, studentId);
            List<TopicRow> rows = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    OffsetDateTime released = rs.getObject("results_released_at", OffsetDateTime.class);
                    rows.add(new TopicRow(rs.getLong("quiz_id"), ReviewPolicy.valueOf(rs.getString("review_policy")),
                            rs.getObject("closes_at", OffsetDateTime.class).toInstant(),
                            released == null ? null : released.toInstant(), rs.getString("code"),
                            rs.getLong("topic_id"), rs.getString("topic"), rs.getInt("seen"), rs.getInt("correct")));
                }
            }
            return rows;
        }
    }

    /** quizId -> {finished official attempts, average score fraction (0..1)} for the given quizzes. */
    public Map<Long, double[]> officialAverages(Connection con, List<Long> quizIds) throws SQLException {
        Map<Long, double[]> map = new HashMap<>();
        if (quizIds.isEmpty()) {
            return map;
        }
        try (PreparedStatement ps = con.prepareStatement("""
                SELECT quiz_id, count(*), avg(score::numeric / max_score)
                FROM quiz_attempts
                WHERE quiz_id = ANY(?) AND mode = 'OFFICIAL' AND status <> 'IN_PROGRESS'
                GROUP BY quiz_id""")) {
            ps.setArray(1, con.createArrayOf("bigint", quizIds.toArray()));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    map.put(rs.getLong(1), new double[]{rs.getInt(2), rs.getDouble(3)});
                }
            }
        }
        return map;
    }

    /** Students (and CRs) currently enrolled in a course: the participation denominator. */
    public int enrolledCount(Connection con, long courseId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT count(*) FROM enrollments WHERE course_id = ?")) {
            ps.setLong(1, courseId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    // ------------------------------------------------------------------ mapping

    private Optional<QuizAttempt> one(Connection con, String sql, long id) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, id);
            List<QuizAttempt> list = map(ps);
            return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
        }
    }

    private List<QuizAttempt> map(PreparedStatement ps) throws SQLException {
        List<QuizAttempt> list = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                int score = rs.getInt("score");
                Integer s = rs.wasNull() ? null : score;
                int max = rs.getInt("max_score");
                Integer m = rs.wasNull() ? null : max;
                OffsetDateTime submitted = rs.getObject("submitted_at", OffsetDateTime.class);
                list.add(new QuizAttempt(rs.getLong("id"), rs.getLong("quiz_id"), rs.getLong("student_id"),
                        rs.getInt("attempt_no"), AttemptMode.valueOf(rs.getString("mode")),
                        AttemptStatus.valueOf(rs.getString("status")),
                        rs.getObject("started_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("deadline_at", OffsetDateTime.class).toInstant(),
                        submitted == null ? null : submitted.toInstant(), s, m));
            }
        }
        return list;
    }
}
