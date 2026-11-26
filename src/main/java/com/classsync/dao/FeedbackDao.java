package com.classsync.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Lecture feedback. Professor-facing queries (report, comments, trend) never select student_id, and for
 * anonymous rows they return NULL instead of the author's name — the identity stays inside the database,
 * where it is needed only to enforce "one feedback per student per lecture".
 */
public class FeedbackDao {

    public record Ratings(int understanding, int clarity, int pace, int usefulness) {
    }

    /** The student's own submission (for editing). */
    public record Own(long id, boolean anonymous, Ratings ratings, String difficult, String suggestions, Instant updatedAt) {
    }

    /** Aggregates; averages are null when there are no submissions. */
    public record Summary(int count, Double understanding, Double clarity, Double pace, Double usefulness) {

        public Double overall() {
            return count == 0 ? null : (understanding + clarity + pace + usefulness) / 4.0;
        }
    }

    /** One written comment. author is null for anonymous feedback. */
    public record Comment(String author, String difficult, String suggestions, Instant createdAt) {
    }

    public record TrendRow(long lectureId, int lectureNo, String topic, Instant startsAt, Summary summary) {
    }

    /** A lecture the student can still give feedback on. */
    public record PendingRow(long lectureId, String courseCode, int lectureNo, String topic, Instant startsAt) {
    }

    public Optional<Own> findOwn(Connection con, long lectureId, long studentId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT * FROM feedback WHERE lecture_id = ? AND student_id = ?")) {
            ps.setLong(1, lectureId);
            ps.setLong(2, studentId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new Own(rs.getLong("id"), rs.getBoolean("anonymous"),
                        new Ratings(rs.getInt("understanding"), rs.getInt("clarity"), rs.getInt("pace"), rs.getInt("usefulness")),
                        rs.getString("difficult_concepts"), rs.getString("suggestions"),
                        rs.getObject("updated_at", OffsetDateTime.class).toInstant()));
            }
        }
    }

    public void insert(Connection con, long lectureId, long studentId, boolean anonymous, Ratings r, String difficult,
                       String suggestions) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("""
                INSERT INTO feedback (lecture_id, student_id, anonymous, understanding, clarity, pace, usefulness,
                    difficult_concepts, suggestions)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""")) {
            ps.setLong(1, lectureId);
            ps.setLong(2, studentId);
            ps.setBoolean(3, anonymous);
            ps.setInt(4, r.understanding());
            ps.setInt(5, r.clarity());
            ps.setInt(6, r.pace());
            ps.setInt(7, r.usefulness());
            ps.setString(8, difficult);
            ps.setString(9, suggestions);
            ps.executeUpdate();
        }
    }

    public void update(Connection con, long id, boolean anonymous, Ratings r, String difficult, String suggestions)
            throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("""
                UPDATE feedback SET anonymous = ?, understanding = ?, clarity = ?, pace = ?, usefulness = ?,
                    difficult_concepts = ?, suggestions = ?, updated_at = now()
                WHERE id = ?""")) {
            ps.setBoolean(1, anonymous);
            ps.setInt(2, r.understanding());
            ps.setInt(3, r.clarity());
            ps.setInt(4, r.pace());
            ps.setInt(5, r.usefulness());
            ps.setString(6, difficult);
            ps.setString(7, suggestions);
            ps.setLong(8, id);
            ps.executeUpdate();
        }
    }

    public Summary summary(Connection con, long lectureId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("""
                SELECT count(*), avg(understanding), avg(clarity), avg(pace), avg(usefulness)
                FROM feedback WHERE lecture_id = ?""")) {
            ps.setLong(1, lectureId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return summary(rs, 1);
            }
        }
    }

    /** Written comments, newest first. The CASE keeps anonymous authors out of the result set entirely. */
    public List<Comment> comments(Connection con, long lectureId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("""
                SELECT CASE WHEN f.anonymous THEN NULL ELSE u.full_name END AS author,
                       f.difficult_concepts, f.suggestions, f.created_at
                FROM feedback f
                JOIN users u ON u.id = f.student_id
                WHERE f.lecture_id = ? AND (f.difficult_concepts <> '' OR f.suggestions <> '')
                ORDER BY f.created_at DESC""")) {
            ps.setLong(1, lectureId);
            List<Comment> list = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new Comment(rs.getString(1), rs.getString(2), rs.getString(3),
                            rs.getObject(4, OffsetDateTime.class).toInstant()));
                }
            }
            return list;
        }
    }

    /** Per-lecture averages for a course, oldest first (lectures without feedback are skipped). */
    public List<TrendRow> trend(Connection con, long courseId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("""
                SELECT l.id, l.lecture_no, l.topic, l.starts_at,
                       count(*), avg(f.understanding), avg(f.clarity), avg(f.pace), avg(f.usefulness)
                FROM feedback f
                JOIN lectures l ON l.id = f.lecture_id
                WHERE l.course_id = ?
                GROUP BY l.id
                ORDER BY l.starts_at""")) {
            ps.setLong(1, courseId);
            List<TrendRow> list = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new TrendRow(rs.getLong(1), rs.getInt(2), rs.getString(3),
                            rs.getObject(4, OffsetDateTime.class).toInstant(), summary(rs, 5)));
                }
            }
            return list;
        }
    }

    /** Open-for-feedback lectures that have started, in the student's courses, without their feedback yet. */
    public List<PendingRow> pending(Connection con, long studentId, List<Long> courseIds) throws SQLException {
        if (courseIds.isEmpty()) {
            return List.of();
        }
        try (PreparedStatement ps = con.prepareStatement("""
                SELECT l.id, c.code, l.lecture_no, l.topic, l.starts_at
                FROM lectures l
                JOIN courses c ON c.id = l.course_id
                WHERE l.course_id = ANY(?) AND l.feedback_open
                  AND l.status IN ('PUBLISHED', 'COMPLETED') AND l.starts_at <= now()
                  AND NOT EXISTS (SELECT 1 FROM feedback f WHERE f.lecture_id = l.id AND f.student_id = ?)
                ORDER BY l.starts_at DESC""")) {
            ps.setArray(1, con.createArrayOf("bigint", courseIds.toArray()));
            ps.setLong(2, studentId);
            List<PendingRow> list = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new PendingRow(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getString(4),
                            rs.getObject(5, OffsetDateTime.class).toInstant()));
                }
            }
            return list;
        }
    }

    private static Summary summary(ResultSet rs, int first) throws SQLException {
        int count = rs.getInt(first);
        if (count == 0) {
            return new Summary(0, null, null, null, null);
        }
        return new Summary(count, rs.getDouble(first + 1), rs.getDouble(first + 2), rs.getDouble(first + 3),
                rs.getDouble(first + 4));
    }
}
