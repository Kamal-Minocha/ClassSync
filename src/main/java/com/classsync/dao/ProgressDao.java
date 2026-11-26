package com.classsync.dao;

import com.classsync.model.ReviewPolicy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Read models for revision mode, performance, badges and recent activity. */
public class ProgressDao {

    /** One meaningful learning action. day is the calendar day in the academic time zone. */
    public record ActivityRow(String kind, Instant at, LocalDate day, String label) {
    }

    /** A finished official attempt with what is needed to apply the release rule. */
    public record ScoreRow(long attemptId, long quizId, String courseCode, String quizTitle, Instant submittedAt,
                           int score, int maxScore, ReviewPolicy policy, Instant closesAt, Instant releasedAt) {
    }

    /** A question the student got wrong or left blank in a finished official attempt. */
    public record MistakeRow(long attemptId, String courseCode, String quizTitle, String prompt, String topic,
                             String chosen, String correct, String explanation, ReviewPolicy policy,
                             Instant closesAt, Instant releasedAt) {
    }

    public record RevisionRow(long id, long topicId, String topic, String courseCode, int unitNo, LocalDate revisedOn,
                              String note) {
    }

    public record Badge(String code, String name, String description, Instant awardedAt) {
    }

    /**
     * Meaningful activity, newest first. Derived from existing records only — viewing a page creates nothing,
     * so it cannot inflate streaks.
     */
    public List<ActivityRow> activity(Connection con, long userId, String zone, int limit) throws SQLException {
        String sql = """
                SELECT kind, at, (at AT TIME ZONE ?)::date AS day, label FROM (
                    SELECT 'QUIZ' AS kind, a.submitted_at AS at, 'Submitted quiz: ' || q.title AS label
                      FROM quiz_attempts a JOIN quizzes q ON q.id = a.quiz_id
                     WHERE a.student_id = ? AND a.status <> 'IN_PROGRESS'
                    UNION ALL
                    SELECT 'ATTENDANCE', t.marked_at, 'Marked present: ' || c.code || ' L' || l.lecture_no
                      FROM attendance t JOIN lectures l ON l.id = t.lecture_id JOIN courses c ON c.id = l.course_id
                     WHERE t.student_id = ? AND t.source = 'CODE'
                    UNION ALL
                    SELECT 'FEEDBACK', f.created_at, 'Gave feedback: L' || l.lecture_no || ' ' || l.topic
                      FROM feedback f JOIN lectures l ON l.id = f.lecture_id WHERE f.student_id = ?
                    UNION ALL
                    SELECT 'NOTE', m.created_at, 'Submitted notes: ' || m.title
                      FROM materials m WHERE m.kind = 'NOTE' AND m.uploader_id = ?
                    UNION ALL
                    SELECT 'DOUBT', d.created_at, 'Asked: ' || d.title FROM discussions d WHERE d.author_id = ?
                    UNION ALL
                    SELECT 'REPLY', r.created_at, 'Replied: ' || d.title
                      FROM discussion_replies r JOIN discussions d ON d.id = r.discussion_id WHERE r.author_id = ?
                    UNION ALL
                    SELECT 'REVISION', h.revised_at, 'Revised: ' || t.name
                      FROM revision_history h JOIN topics t ON t.id = h.topic_id WHERE h.student_id = ?
                    UNION ALL
                    SELECT 'TASK', s.done_at, 'Completed task: ' || s.title
                      FROM study_tasks s WHERE s.user_id = ? AND s.done_at IS NOT NULL
                ) x
                ORDER BY at DESC
                LIMIT ?""";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, zone);
            for (int i = 2; i <= 9; i++) {
                ps.setLong(i, userId);
            }
            ps.setInt(10, limit);
            List<ActivityRow> list = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new ActivityRow(rs.getString(1), rs.getObject(2, OffsetDateTime.class).toInstant(),
                            rs.getObject(3, LocalDate.class), rs.getString(4)));
                }
            }
            return list;
        }
    }

    public List<ScoreRow> officialScores(Connection con, long studentId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("""
                SELECT a.id, q.id, c.code, q.title, a.submitted_at, a.score, a.max_score,
                       q.review_policy, q.closes_at, q.results_released_at
                FROM quiz_attempts a
                JOIN quizzes q ON q.id = a.quiz_id
                JOIN courses c ON c.id = q.course_id
                WHERE a.student_id = ? AND a.mode = 'OFFICIAL' AND a.status <> 'IN_PROGRESS'
                ORDER BY a.submitted_at""")) {
            ps.setLong(1, studentId);
            List<ScoreRow> list = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    OffsetDateTime rel = rs.getObject(10, OffsetDateTime.class);
                    list.add(new ScoreRow(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                            rs.getObject(5, OffsetDateTime.class).toInstant(), rs.getInt(6), rs.getInt(7),
                            ReviewPolicy.valueOf(rs.getString(8)), rs.getObject(9, OffsetDateTime.class).toInstant(),
                            rel == null ? null : rel.toInstant()));
                }
            }
            return list;
        }
    }

    /** Wrong or unanswered questions of finished official attempts (release rule applied by the service). */
    public List<MistakeRow> mistakes(Connection con, long studentId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("""
                SELECT a.id, c.code, q.title, qu.prompt, t.name, ch.text, ok.text, qu.explanation,
                       q.review_policy, q.closes_at, q.results_released_at
                FROM quiz_attempts a
                JOIN quizzes q ON q.id = a.quiz_id
                JOIN courses c ON c.id = q.course_id
                JOIN questions qu ON qu.quiz_id = q.id
                JOIN question_options ok ON ok.question_id = qu.id AND ok.is_correct
                LEFT JOIN topics t ON t.id = qu.topic_id
                LEFT JOIN answers an ON an.attempt_id = a.id AND an.question_id = qu.id
                LEFT JOIN question_options ch ON ch.id = an.option_id
                WHERE a.student_id = ? AND a.mode = 'OFFICIAL' AND a.status <> 'IN_PROGRESS'
                  AND (an.option_id IS NULL OR NOT an.is_correct)
                ORDER BY a.submitted_at DESC, qu.position""")) {
            ps.setLong(1, studentId);
            List<MistakeRow> list = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    OffsetDateTime rel = rs.getObject(11, OffsetDateTime.class);
                    list.add(new MistakeRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                            ReviewPolicy.valueOf(rs.getString(9)), rs.getObject(10, OffsetDateTime.class).toInstant(),
                            rel == null ? null : rel.toInstant()));
                }
            }
            return list;
        }
    }

    // ------------------------------------------------------------------ revision

    /** Records a revision; false if the topic was already marked revised that day. */
    public boolean addRevision(Connection con, long studentId, long topicId, LocalDate day, String note) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("""
                INSERT INTO revision_history (student_id, topic_id, revised_on, note) VALUES (?, ?, ?, ?)
                ON CONFLICT (student_id, topic_id, revised_on) DO NOTHING""")) {
            ps.setLong(1, studentId);
            ps.setLong(2, topicId);
            ps.setObject(3, day);
            ps.setString(4, note);
            return ps.executeUpdate() == 1;
        }
    }

    public List<RevisionRow> revisions(Connection con, long studentId, int limit) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("""
                SELECT h.id, t.id, t.name, c.code, t.unit_no, h.revised_on, h.note
                FROM revision_history h JOIN topics t ON t.id = h.topic_id JOIN courses c ON c.id = t.course_id
                WHERE h.student_id = ? ORDER BY h.revised_at DESC LIMIT ?""")) {
            ps.setLong(1, studentId);
            ps.setInt(2, limit);
            List<RevisionRow> list = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new RevisionRow(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getInt(5),
                            rs.getObject(6, LocalDate.class), rs.getString(7)));
                }
            }
            return list;
        }
    }

    // ------------------------------------------------------------------ badges

    public List<Badge> catalogue(Connection con, long studentId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("""
                SELECT a.code, a.name, a.description, s.awarded_at
                FROM achievements a
                LEFT JOIN student_achievements s ON s.achievement_code = a.code AND s.student_id = ?
                ORDER BY a.code""")) {
            ps.setLong(1, studentId);
            List<Badge> list = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    OffsetDateTime at = rs.getObject(4, OffsetDateTime.class);
                    list.add(new Badge(rs.getString(1), rs.getString(2), rs.getString(3), at == null ? null : at.toInstant()));
                }
            }
            return list;
        }
    }

    /** Awards once (primary key); returns true only the first time. */
    public boolean award(Connection con, long studentId, String code) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "INSERT INTO student_achievements (student_id, achievement_code) VALUES (?, ?) ON CONFLICT DO NOTHING")) {
            ps.setLong(1, studentId);
            ps.setString(2, code);
            return ps.executeUpdate() == 1;
        }
    }

    public int replyUpvotesReceived(Connection con, long userId) throws SQLException {
        return count(con, """
                SELECT count(*) FROM reply_votes v JOIN discussion_replies r ON r.id = v.reply_id
                WHERE r.author_id = ? AND r.hidden_at IS NULL""", userId);
    }

    public int approvedNotes(Connection con, long userId) throws SQLException {
        return count(con, """
                SELECT count(*) FROM materials
                WHERE kind = 'NOTE' AND review_status = 'APPROVED' AND uploader_id = ? AND archived_at IS NULL""", userId);
    }

    /** Distinct academic-zone days with meaningful activity (all time). */
    public Set<LocalDate> activeDays(Connection con, long userId, String zone) throws SQLException {
        Set<LocalDate> days = new TreeSet<>();
        for (ActivityRow r : activity(con, userId, zone, 100_000)) {
            days.add(r.day());
        }
        return days;
    }

    private static int count(Connection con, String sql, long id) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }
}
