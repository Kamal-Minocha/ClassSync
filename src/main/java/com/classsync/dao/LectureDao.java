package com.classsync.dao;

import com.classsync.model.Lecture;
import com.classsync.model.LectureStatus;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Lecture persistence. Visibility (drafts) is decided by the caller via course-id arrays. */
public class LectureDao {

    /** Editable lecture fields. */
    public record LectureData(long courseId, int lectureNo, int unitNo, Long topicId, String topic,
                              String description, String objectives, String preparation, String room,
                              Instant startsAt, Instant endsAt) {
    }

    private static final String SELECT = """
            SELECT l.*, c.code AS course_code, c.name AS course_name
            FROM lectures l JOIN courses c ON c.id = l.course_id
            """;

    public long insert(Connection con, LectureData d, long createdBy) throws SQLException {
        String sql = """
                INSERT INTO lectures (course_id, lecture_no, unit_no, topic_id, topic, description, objectives,
                                      preparation, room, starts_at, ends_at, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""";
        try (PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bind(ps, d);
            ps.setLong(12, createdBy);
            ps.executeUpdate();
            try (ResultSet k = ps.getGeneratedKeys()) {
                k.next();
                return k.getLong(1);
            }
        }
    }

    public void update(Connection con, long id, LectureData d) throws SQLException {
        String sql = """
                UPDATE lectures SET course_id = ?, lecture_no = ?, unit_no = ?, topic_id = ?, topic = ?,
                    description = ?, objectives = ?, preparation = ?, room = ?, starts_at = ?, ends_at = ?,
                    updated_at = now()
                WHERE id = ?""";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            bind(ps, d);
            ps.setLong(12, id);
            ps.executeUpdate();
        }
    }

    private static void bind(PreparedStatement ps, LectureData d) throws SQLException {
        ps.setLong(1, d.courseId());
        ps.setInt(2, d.lectureNo());
        ps.setInt(3, d.unitNo());
        if (d.topicId() == null) {
            ps.setNull(4, Types.BIGINT);
        } else {
            ps.setLong(4, d.topicId());
        }
        ps.setString(5, d.topic());
        ps.setString(6, d.description());
        ps.setString(7, d.objectives());
        ps.setString(8, d.preparation());
        ps.setString(9, d.room());
        ps.setObject(10, d.startsAt().atOffset(ZoneOffset.UTC));
        ps.setObject(11, d.endsAt().atOffset(ZoneOffset.UTC));
    }

    /**
     * Conditional status change: only succeeds if the lecture is still in the expected state,
     * so two users changing it at once cannot both win.
     */
    public boolean changeStatus(Connection con, long id, LectureStatus expected, LectureStatus next) throws SQLException {
        String sql = "UPDATE lectures SET status = ?, updated_at = now() WHERE id = ? AND status = ?";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, next.name());
            ps.setLong(2, id);
            ps.setString(3, expected.name());
            return ps.executeUpdate() == 1;
        }
    }

    public void setFeedbackSettings(Connection con, long id, boolean open, boolean anonymous) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "UPDATE lectures SET feedback_open = ?, feedback_anonymous = ?, updated_at = now() WHERE id = ?")) {
            ps.setBoolean(1, open);
            ps.setBoolean(2, anonymous);
            ps.setLong(3, id);
            ps.executeUpdate();
        }
    }

    public int deleteDraft(Connection con, long id) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("DELETE FROM lectures WHERE id = ? AND status = 'DRAFT'")) {
            ps.setLong(1, id);
            return ps.executeUpdate();
        }
    }

    public Optional<Lecture> findById(Connection con, long id) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(SELECT + " WHERE l.id = ?")) {
            ps.setLong(1, id);
            List<Lecture> list = map(ps);
            return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
        }
    }

    /** Row lock for transactions that must not race with status changes (attendance, quizzes). */
    public Optional<Lecture> lockById(Connection con, long id) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(SELECT + " WHERE l.id = ? FOR UPDATE OF l")) {
            ps.setLong(1, id);
            List<Lecture> list = map(ps);
            return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
        }
    }

    /**
     * Shared row lock: many students may mark attendance at once, while finalization (FOR UPDATE)
     * waits for them, or they wait for it — never both at the same time.
     */
    public Optional<Lecture> lockShared(Connection con, long id) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(SELECT + " WHERE l.id = ? FOR SHARE OF l")) {
            ps.setLong(1, id);
            List<Lecture> list = map(ps);
            return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
        }
    }

    public int nextLectureNo(Connection con, long courseId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT coalesce(max(lecture_no), 0) + 1 FROM lectures WHERE course_id = ?")) {
            ps.setLong(1, courseId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /**
     * Lectures in the given courses, optionally within [from, to).
     *
     * @param courseIds       courses the user may see
     * @param draftCourseIds  subset of courses in which the user may also see DRAFT lectures
     * @param ascending       order by start time ascending (upcoming) or descending (previous)
     * @param limit           maximum rows (bounded loads)
     */
    public List<Lecture> list(Connection con, List<Long> courseIds, List<Long> draftCourseIds,
                              Instant from, Instant to, boolean ascending, int limit) throws SQLException {
        if (courseIds.isEmpty()) {
            return List.of();
        }
        String sql = SELECT + """
                 WHERE l.course_id = ANY(?) AND (l.status <> 'DRAFT' OR l.course_id = ANY(?))
                   AND (?::timestamptz IS NULL OR l.ends_at > ?::timestamptz)
                   AND (?::timestamptz IS NULL OR l.starts_at < ?::timestamptz)
                 ORDER BY l.starts_at""" + (ascending ? " ASC" : " DESC") + ", l.id LIMIT ?";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            Array all = con.createArrayOf("bigint", courseIds.toArray());
            Array drafts = con.createArrayOf("bigint", draftCourseIds.toArray());
            ps.setArray(1, all);
            ps.setArray(2, drafts);
            OffsetDateTime f = from == null ? null : from.atOffset(ZoneOffset.UTC);
            OffsetDateTime t = to == null ? null : to.atOffset(ZoneOffset.UTC);
            ps.setObject(3, f);
            ps.setObject(4, f);
            ps.setObject(5, t);
            ps.setObject(6, t);
            ps.setInt(7, limit);
            return map(ps);
        }
    }

    /** Text search over topic / description in the given courses (parameterised ILIKE). */
    public List<Lecture> search(Connection con, List<Long> courseIds, List<Long> draftCourseIds, String query, int limit)
            throws SQLException {
        if (courseIds.isEmpty()) {
            return List.of();
        }
        String sql = SELECT + """
                 WHERE l.course_id = ANY(?) AND (l.status <> 'DRAFT' OR l.course_id = ANY(?))
                   AND (l.topic ILIKE ? OR l.description ILIKE ? OR l.objectives ILIKE ?)
                 ORDER BY l.starts_at DESC LIMIT ?""";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setArray(1, con.createArrayOf("bigint", courseIds.toArray()));
            ps.setArray(2, con.createArrayOf("bigint", draftCourseIds.toArray()));
            String like = "%" + escapeLike(query) + "%";
            ps.setString(3, like);
            ps.setString(4, like);
            ps.setString(5, like);
            ps.setInt(6, limit);
            return map(ps);
        }
    }

    /** Escapes LIKE wildcards so user input is matched literally. */
    public static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private List<Lecture> map(PreparedStatement ps) throws SQLException {
        List<Lecture> list = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                long topicId = rs.getLong("topic_id");
                Long topic = rs.wasNull() ? null : topicId;
                OffsetDateTime finalized = rs.getObject("attendance_finalized_at", OffsetDateTime.class);
                list.add(new Lecture(rs.getLong("id"), rs.getLong("course_id"), rs.getString("course_code"),
                        rs.getString("course_name"), rs.getInt("lecture_no"), rs.getInt("unit_no"), topic,
                        rs.getString("topic"), rs.getString("description"), rs.getString("objectives"),
                        rs.getString("preparation"), rs.getString("room"),
                        rs.getObject("starts_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("ends_at", OffsetDateTime.class).toInstant(),
                        LectureStatus.valueOf(rs.getString("status")), rs.getBoolean("feedback_open"),
                        rs.getBoolean("feedback_anonymous"), finalized == null ? null : finalized.toInstant()));
            }
        }
        return list;
    }
}
