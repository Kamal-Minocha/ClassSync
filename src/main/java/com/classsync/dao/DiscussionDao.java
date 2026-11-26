package com.classsync.dao;

import com.classsync.model.Discussion;
import com.classsync.model.DiscussionReply;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Doubts, replies, reply votes and the doubt heatmap aggregate. */
public class DiscussionDao {

    /** Filter for listing. Hidden doubts are only included for courses in moderatedCourseIds. */
    public record Filter(List<Long> courseIds, List<Long> moderatedCourseIds, Long lectureId, String text,
                         boolean unresolvedOnly, Long authorId, int limit) {
    }

    /** Heatmap cell: unresolved and total (visible) doubts for one syllabus topic (topicId null = no topic). */
    public record HeatRow(Long topicId, String topic, Integer unitNo, int unresolved, int total) {
    }

    private static final String SELECT = """
            SELECT d.*, c.code AS course_code, l.lecture_no, t.name AS topic_name, u.full_name AS author_name,
                   r.full_name AS resolved_by_name,
                   (SELECT count(*) FROM discussion_replies x WHERE x.discussion_id = d.id AND x.hidden_at IS NULL) AS reply_count,
                   EXISTS (SELECT 1 FROM discussion_replies x WHERE x.discussion_id = d.id AND x.hidden_at IS NULL
                           AND x.author_id = c.professor_id) AS professor_answered
            FROM discussions d
            JOIN courses c ON c.id = d.course_id
            JOIN users u ON u.id = d.author_id
            LEFT JOIN users r ON r.id = d.resolved_by
            LEFT JOIN lectures l ON l.id = d.lecture_id
            LEFT JOIN topics t ON t.id = d.topic_id
            """;

    public long insert(Connection con, long courseId, Long lectureId, Long topicId, long authorId, String title, String body)
            throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("""
                INSERT INTO discussions (course_id, lecture_id, topic_id, author_id, title, body)
                VALUES (?, ?, ?, ?, ?, ?)""", Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, courseId);
            MaterialDao.setNullableLong(ps, 2, lectureId);
            MaterialDao.setNullableLong(ps, 3, topicId);
            ps.setLong(4, authorId);
            ps.setString(5, title);
            ps.setString(6, body);
            ps.executeUpdate();
            try (ResultSet k = ps.getGeneratedKeys()) {
                k.next();
                return k.getLong(1);
            }
        }
    }

    public Optional<Discussion> findById(Connection con, long id) throws SQLException {
        return one(con, SELECT + " WHERE d.id = ?", id);
    }

    public Optional<Discussion> lockById(Connection con, long id) throws SQLException {
        return one(con, SELECT + " WHERE d.id = ? FOR UPDATE OF d", id);
    }

    public List<Discussion> list(Connection con, Filter f) throws SQLException {
        if (f.courseIds().isEmpty()) {
            return List.of();
        }
        String q = f.text() == null || f.text().isBlank() ? null : "%" + LectureDao.escapeLike(f.text().trim()) + "%";
        try (PreparedStatement ps = con.prepareStatement(SELECT + """
                 WHERE d.course_id = ANY(?)
                   AND (d.hidden_at IS NULL OR d.course_id = ANY(?))
                   AND (?::bigint IS NULL OR d.lecture_id = ?::bigint)
                   AND (?::text IS NULL OR d.title ILIKE ? OR d.body ILIKE ?)
                   AND (NOT ? OR d.resolved_at IS NULL)
                   AND (?::bigint IS NULL OR d.author_id = ?::bigint)
                 ORDER BY (d.resolved_at IS NULL) DESC, d.created_at DESC
                 LIMIT ?""")) {
            ps.setArray(1, con.createArrayOf("bigint", f.courseIds().toArray()));
            ps.setArray(2, con.createArrayOf("bigint", f.moderatedCourseIds().toArray()));
            MaterialDao.setNullableLong(ps, 3, f.lectureId());
            MaterialDao.setNullableLong(ps, 4, f.lectureId());
            ps.setString(5, q);
            ps.setString(6, q);
            ps.setString(7, q);
            ps.setBoolean(8, f.unresolvedOnly());
            MaterialDao.setNullableLong(ps, 9, f.authorId());
            MaterialDao.setNullableLong(ps, 10, f.authorId());
            ps.setInt(11, f.limit());
            return map(ps);
        }
    }

    public void setResolved(Connection con, long id, Long resolverId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(resolverId == null
                ? "UPDATE discussions SET resolved_at = NULL, resolved_by = NULL WHERE id = ?"
                : "UPDATE discussions SET resolved_at = now(), resolved_by = ? WHERE id = ?")) {
            if (resolverId == null) {
                ps.setLong(1, id);
            } else {
                ps.setLong(1, resolverId);
                ps.setLong(2, id);
            }
            ps.executeUpdate();
        }
    }

    /** Hides (moderatorId set) or unhides (null) a doubt or reply. table is chosen by the DAO, never by input. */
    public void setHidden(Connection con, boolean reply, long id, Long moderatorId) throws SQLException {
        String table = reply ? "discussion_replies" : "discussions";
        try (PreparedStatement ps = con.prepareStatement(moderatorId == null
                ? "UPDATE " + table + " SET hidden_at = NULL, hidden_by = NULL WHERE id = ?"
                : "UPDATE " + table + " SET hidden_at = now(), hidden_by = ? WHERE id = ?")) {
            if (moderatorId == null) {
                ps.setLong(1, id);
            } else {
                ps.setLong(1, moderatorId);
                ps.setLong(2, id);
            }
            ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------ replies

    public long insertReply(Connection con, long discussionId, long authorId, String body) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "INSERT INTO discussion_replies (discussion_id, author_id, body) VALUES (?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, discussionId);
            ps.setLong(2, authorId);
            ps.setString(3, body);
            ps.executeUpdate();
            try (ResultSet k = ps.getGeneratedKeys()) {
                k.next();
                return k.getLong(1);
            }
        }
    }

    /** Returns {discussionId, authorId} of a reply. */
    public Optional<long[]> replyOwner(Connection con, long replyId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT discussion_id, author_id FROM discussion_replies WHERE id = ?")) {
            ps.setLong(1, replyId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(new long[]{rs.getLong(1), rs.getLong(2)}) : Optional.empty();
            }
        }
    }

    /** Replies oldest first; hidden ones only when includeHidden. */
    public List<DiscussionReply> replies(Connection con, long discussionId, long professorId, long viewerId,
                                         boolean includeHidden) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("""
                SELECT r.*, u.full_name,
                       (SELECT count(*) FROM reply_votes v WHERE v.reply_id = r.id) AS votes,
                       EXISTS (SELECT 1 FROM reply_votes v WHERE v.reply_id = r.id AND v.voter_id = ?) AS voted
                FROM discussion_replies r JOIN users u ON u.id = r.author_id
                WHERE r.discussion_id = ? AND (? OR r.hidden_at IS NULL)
                ORDER BY r.created_at, r.id""")) {
            ps.setLong(1, viewerId);
            ps.setLong(2, discussionId);
            ps.setBoolean(3, includeHidden);
            List<DiscussionReply> list = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new DiscussionReply(rs.getLong("id"), discussionId, rs.getLong("author_id"),
                            rs.getString("full_name"), rs.getLong("author_id") == professorId, rs.getString("body"),
                            rs.getObject("hidden_at") != null, rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                            rs.getInt("votes"), rs.getBoolean("voted")));
                }
            }
            return list;
        }
    }

    public boolean vote(Connection con, long replyId, long voterId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "INSERT INTO reply_votes (reply_id, voter_id) VALUES (?, ?) ON CONFLICT DO NOTHING")) {
            ps.setLong(1, replyId);
            ps.setLong(2, voterId);
            return ps.executeUpdate() == 1;
        }
    }

    public void unvote(Connection con, long replyId, long voterId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("DELETE FROM reply_votes WHERE reply_id = ? AND voter_id = ?")) {
            ps.setLong(1, replyId);
            ps.setLong(2, voterId);
            ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------ heatmap

    /** Visible doubts per syllabus topic of a course, most unresolved first. */
    public List<HeatRow> heatmap(Connection con, long courseId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("""
                SELECT d.topic_id, coalesce(t.name, 'General (no topic)') AS topic, t.unit_no,
                       count(*) FILTER (WHERE d.resolved_at IS NULL) AS unresolved, count(*) AS total
                FROM discussions d
                LEFT JOIN topics t ON t.id = d.topic_id
                WHERE d.course_id = ? AND d.hidden_at IS NULL
                GROUP BY d.topic_id, t.name, t.unit_no
                ORDER BY unresolved DESC, total DESC, topic""")) {
            ps.setLong(1, courseId);
            List<HeatRow> list = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long topic = rs.getLong(1);
                    Long topicId = rs.wasNull() ? null : topic;
                    int unit = rs.getInt(3);
                    list.add(new HeatRow(topicId, rs.getString(2), rs.wasNull() ? null : unit, rs.getInt(4), rs.getInt(5)));
                }
            }
            return list;
        }
    }

    // ------------------------------------------------------------------ mapping

    private Optional<Discussion> one(Connection con, String sql, long id) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, id);
            List<Discussion> list = map(ps);
            return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
        }
    }

    private List<Discussion> map(PreparedStatement ps) throws SQLException {
        List<Discussion> list = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                long lecture = rs.getLong("lecture_id");
                Long lectureId = rs.wasNull() ? null : lecture;
                int no = rs.getInt("lecture_no");
                Integer lectureNo = rs.wasNull() ? null : no;
                long topic = rs.getLong("topic_id");
                Long topicId = rs.wasNull() ? null : topic;
                OffsetDateTime resolved = rs.getObject("resolved_at", OffsetDateTime.class);
                list.add(new Discussion(rs.getLong("id"), rs.getLong("course_id"), rs.getString("course_code"), lectureId,
                        lectureNo, topicId, rs.getString("topic_name"), rs.getLong("author_id"), rs.getString("author_name"),
                        rs.getString("title"), rs.getString("body"), resolved == null ? null : resolved.toInstant(),
                        rs.getString("resolved_by_name"), rs.getObject("hidden_at") != null,
                        rs.getObject("created_at", OffsetDateTime.class).toInstant(), rs.getInt("reply_count"),
                        rs.getBoolean("professor_answered")));
            }
        }
        return list;
    }
}
