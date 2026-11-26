package com.classsync.dao;

import com.classsync.model.Question;
import com.classsync.model.QuestionKind;
import com.classsync.model.QuestionOption;
import com.classsync.model.Quiz;
import com.classsync.model.QuizStatus;
import com.classsync.model.ReviewPolicy;
import com.classsync.model.StudentQuestion;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Quizzes, questions and options.
 *
 * Two different question queries exist on purpose:
 *   questions(...)        — editor view WITH is_correct and explanation (course professor only)
 *   studentQuestions(...) — attempt view; the SQL does not select is_correct or explanation at all
 */
public class QuizDao {

    /** Validated settings written by insert/update. */
    public record QuizData(long courseId, Long lectureId, String title, String instructions, Instant opensAt,
                           Instant closesAt, int durationMinutes, ReviewPolicy reviewPolicy, boolean allowPractice) {
    }

    /** Validated question; options are in display order, correctIndex is 0-based. */
    public record QuestionData(QuestionKind kind, String prompt, int marks, Long topicId, String explanation,
                               List<String> options, int correctIndex) {
    }

    private static final String SELECT = """
            SELECT q.*, c.code AS course_code, l.lecture_no,
                   (SELECT count(*) FROM questions x WHERE x.quiz_id = q.id) AS question_count,
                   (SELECT coalesce(sum(x.marks), 0) FROM questions x WHERE x.quiz_id = q.id) AS total_marks,
                   (SELECT count(*) FROM quiz_attempts a
                     WHERE a.quiz_id = q.id AND a.mode = 'OFFICIAL' AND a.status <> 'IN_PROGRESS') AS submissions
            FROM quizzes q
            JOIN courses c ON c.id = q.course_id
            LEFT JOIN lectures l ON l.id = q.lecture_id
            """;

    // ------------------------------------------------------------------ quizzes

    public long insert(Connection con, QuizData d, long createdBy) throws SQLException {
        String sql = """
                INSERT INTO quizzes (course_id, lecture_id, title, instructions, opens_at, closes_at,
                    duration_minutes, review_policy, allow_practice, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""";
        try (PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, d.courseId());
            MaterialDao.setNullableLong(ps, 2, d.lectureId());
            ps.setString(3, d.title());
            ps.setString(4, d.instructions());
            ps.setTimestamp(5, Timestamp.from(d.opensAt()));
            ps.setTimestamp(6, Timestamp.from(d.closesAt()));
            ps.setInt(7, d.durationMinutes());
            ps.setString(8, d.reviewPolicy().name());
            ps.setBoolean(9, d.allowPractice());
            ps.setLong(10, createdBy);
            ps.executeUpdate();
            try (ResultSet k = ps.getGeneratedKeys()) {
                k.next();
                return k.getLong(1);
            }
        }
    }

    public void update(Connection con, long id, QuizData d) throws SQLException {
        String sql = """
                UPDATE quizzes SET lecture_id = ?, title = ?, instructions = ?, opens_at = ?, closes_at = ?,
                    duration_minutes = ?, review_policy = ?, allow_practice = ?, updated_at = now()
                WHERE id = ?""";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            MaterialDao.setNullableLong(ps, 1, d.lectureId());
            ps.setString(2, d.title());
            ps.setString(3, d.instructions());
            ps.setTimestamp(4, Timestamp.from(d.opensAt()));
            ps.setTimestamp(5, Timestamp.from(d.closesAt()));
            ps.setInt(6, d.durationMinutes());
            ps.setString(7, d.reviewPolicy().name());
            ps.setBoolean(8, d.allowPractice());
            ps.setLong(9, id);
            ps.executeUpdate();
        }
    }

    public void setStatus(Connection con, long id, QuizStatus status) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("UPDATE quizzes SET status = ?, updated_at = now() WHERE id = ?")) {
            ps.setString(1, status.name());
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    /** Sets results_released_at once; returns false if results were already released. */
    public boolean releaseResults(Connection con, long id) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "UPDATE quizzes SET results_released_at = now(), updated_at = now() WHERE id = ? AND results_released_at IS NULL")) {
            ps.setLong(1, id);
            return ps.executeUpdate() == 1;
        }
    }

    public void delete(Connection con, long id) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("DELETE FROM quizzes WHERE id = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    public Optional<Quiz> findById(Connection con, long id) throws SQLException {
        return one(con, SELECT + " WHERE q.id = ?", id);
    }

    /** Exclusive lock: used by every editor change so it cannot interleave with a student starting an attempt. */
    public Optional<Quiz> lockForUpdate(Connection con, long id) throws SQLException {
        return one(con, SELECT + " WHERE q.id = ? FOR UPDATE OF q", id);
    }

    /** Shared lock: taken when an attempt starts; many students can start at once, editors wait. */
    public Optional<Quiz> lockForShare(Connection con, long id) throws SQLException {
        return one(con, SELECT + " WHERE q.id = ? FOR SHARE OF q", id);
    }

    /**
     * Quizzes in courseIds; drafts only for courses in draftCourseIds. lectureId narrows to one lecture.
     * Soonest-opening first for upcoming work, most recent first otherwise is decided by the caller.
     */
    public List<Quiz> list(Connection con, List<Long> courseIds, List<Long> draftCourseIds, Long lectureId, int limit)
            throws SQLException {
        if (courseIds.isEmpty()) {
            return List.of();
        }
        String sql = SELECT + """
                 WHERE q.course_id = ANY(?)
                   AND (q.status = 'PUBLISHED' OR q.course_id = ANY(?))
                   AND (?::bigint IS NULL OR q.lecture_id = ?::bigint)
                 ORDER BY q.opens_at DESC, q.id DESC
                 LIMIT ?""";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setArray(1, con.createArrayOf("bigint", courseIds.toArray()));
            ps.setArray(2, con.createArrayOf("bigint", draftCourseIds.toArray()));
            MaterialDao.setNullableLong(ps, 3, lectureId);
            MaterialDao.setNullableLong(ps, 4, lectureId);
            ps.setInt(5, limit);
            return map(ps);
        }
    }

    public boolean hasAttempts(Connection con, long quizId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT EXISTS (SELECT 1 FROM quiz_attempts WHERE quiz_id = ?)")) {
            ps.setLong(1, quizId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        }
    }

    // ------------------------------------------------------------------ questions

    public long insertQuestion(Connection con, long quizId, QuestionData q) throws SQLException {
        int position;
        try (PreparedStatement ps = con.prepareStatement("SELECT coalesce(max(position), 0) + 1 FROM questions WHERE quiz_id = ?")) {
            ps.setLong(1, quizId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                position = rs.getInt(1);
            }
        }
        long id;
        String sql = """
                INSERT INTO questions (quiz_id, position, kind, prompt, marks, topic_id, explanation)
                VALUES (?, ?, ?, ?, ?, ?, ?)""";
        try (PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, quizId);
            ps.setInt(2, position);
            ps.setString(3, q.kind().name());
            ps.setString(4, q.prompt());
            ps.setInt(5, q.marks());
            MaterialDao.setNullableLong(ps, 6, q.topicId());
            ps.setString(7, q.explanation());
            ps.executeUpdate();
            try (ResultSet k = ps.getGeneratedKeys()) {
                k.next();
                id = k.getLong(1);
            }
        }
        insertOptions(con, id, q);
        return id;
    }

    /** Replaces a question's text and options. Only called while the quiz has no attempts. */
    public void updateQuestion(Connection con, long questionId, QuestionData q) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "UPDATE questions SET kind = ?, prompt = ?, marks = ?, topic_id = ?, explanation = ? WHERE id = ?")) {
            ps.setString(1, q.kind().name());
            ps.setString(2, q.prompt());
            ps.setInt(3, q.marks());
            MaterialDao.setNullableLong(ps, 4, q.topicId());
            ps.setString(5, q.explanation());
            ps.setLong(6, questionId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = con.prepareStatement("DELETE FROM question_options WHERE question_id = ?")) {
            ps.setLong(1, questionId);
            ps.executeUpdate();
        }
        insertOptions(con, questionId, q);
    }

    public void deleteQuestion(Connection con, long questionId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("DELETE FROM questions WHERE id = ?")) {
            ps.setLong(1, questionId);
            ps.executeUpdate();
        }
    }

    private void insertOptions(Connection con, long questionId, QuestionData q) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "INSERT INTO question_options (question_id, position, text, is_correct) VALUES (?, ?, ?, ?)")) {
            for (int i = 0; i < q.options().size(); i++) {
                ps.setLong(1, questionId);
                ps.setInt(2, i + 1);
                ps.setString(3, q.options().get(i));
                ps.setBoolean(4, i == q.correctIndex());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /** Quiz id of a question, or empty. */
    public Optional<Long> quizOfQuestion(Connection con, long questionId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT quiz_id FROM questions WHERE id = ?")) {
            ps.setLong(1, questionId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getLong(1)) : Optional.empty();
            }
        }
    }

    /** Editor view, including the answer key. */
    public List<Question> questions(Connection con, long quizId) throws SQLException {
        String sql = """
                SELECT qu.id, qu.quiz_id, qu.position, qu.kind, qu.prompt, qu.marks, qu.topic_id, t.name AS topic_name,
                       qu.explanation, o.id AS option_id, o.position AS option_position, o.text, o.is_correct
                FROM questions qu
                LEFT JOIN topics t ON t.id = qu.topic_id
                LEFT JOIN question_options o ON o.question_id = qu.id
                WHERE qu.quiz_id = ?
                ORDER BY qu.position, o.position""";
        Map<Long, Question> byId = new LinkedHashMap<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, quizId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long id = rs.getLong("id");
                    Question q = byId.get(id);
                    if (q == null) {
                        long topic = rs.getLong("topic_id");
                        q = new Question(id, rs.getLong("quiz_id"), rs.getInt("position"),
                                QuestionKind.valueOf(rs.getString("kind")), rs.getString("prompt"), rs.getInt("marks"),
                                rs.wasNull() ? null : topic, rs.getString("topic_name"), rs.getString("explanation"),
                                new ArrayList<>());
                        byId.put(id, q);
                    }
                    long optionId = rs.getLong("option_id");
                    if (!rs.wasNull()) {
                        q.options().add(new QuestionOption(optionId, rs.getInt("option_position"), rs.getString("text"),
                                rs.getBoolean("is_correct")));
                    }
                }
            }
        }
        return List.copyOf(byId.values());
    }

    /** Attempt view: no is_correct, no explanation — the answer key never leaves the database. */
    public List<StudentQuestion> studentQuestions(Connection con, long quizId) throws SQLException {
        String sql = """
                SELECT qu.id, qu.position, qu.kind, qu.prompt, qu.marks, o.id AS option_id, o.text
                FROM questions qu
                JOIN question_options o ON o.question_id = qu.id
                WHERE qu.quiz_id = ?
                ORDER BY qu.position, o.position""";
        Map<Long, StudentQuestion> byId = new LinkedHashMap<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, quizId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long id = rs.getLong("id");
                    StudentQuestion q = byId.get(id);
                    if (q == null) {
                        q = new StudentQuestion(id, rs.getInt("position"), QuestionKind.valueOf(rs.getString("kind")),
                                rs.getString("prompt"), rs.getInt("marks"), new ArrayList<>());
                        byId.put(id, q);
                    }
                    q.choices().add(new StudentQuestion.Choice(rs.getLong("option_id"), rs.getString("text")));
                }
            }
        }
        return List.copyOf(byId.values());
    }

    // ------------------------------------------------------------------ mapping

    private Optional<Quiz> one(Connection con, String sql, long id) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, id);
            List<Quiz> list = map(ps);
            return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
        }
    }

    private List<Quiz> map(PreparedStatement ps) throws SQLException {
        List<Quiz> list = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                long lecture = rs.getLong("lecture_id");
                Long lectureId = rs.wasNull() ? null : lecture;
                int no = rs.getInt("lecture_no");
                Integer lectureNo = rs.wasNull() ? null : no;
                OffsetDateTime released = rs.getObject("results_released_at", OffsetDateTime.class);
                list.add(new Quiz(rs.getLong("id"), rs.getLong("course_id"), rs.getString("course_code"), lectureId,
                        lectureNo, rs.getString("title"), rs.getString("instructions"),
                        rs.getObject("opens_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("closes_at", OffsetDateTime.class).toInstant(), rs.getInt("duration_minutes"),
                        QuizStatus.valueOf(rs.getString("status")), ReviewPolicy.valueOf(rs.getString("review_policy")),
                        released == null ? null : released.toInstant(), rs.getBoolean("allow_practice"),
                        rs.getInt("question_count"), rs.getInt("total_marks"), rs.getInt("submissions")));
            }
        }
        return list;
    }
}
