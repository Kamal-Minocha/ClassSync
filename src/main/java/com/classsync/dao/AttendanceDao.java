package com.classsync.dao;

import com.classsync.model.AttendanceCorrection;
import com.classsync.model.AttendanceRecord;
import com.classsync.model.AttendanceSession;
import com.classsync.model.AttendanceStatus;

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
 * Attendance sessions, records, wrong-code counters and corrections.
 * Expiry times are computed by PostgreSQL (clock_timestamp()), never by the desktop clock.
 */
public class AttendanceDao {

    /** Attendance of one student in one course over counted lectures. */
    public record CourseTotals(long courseId, String courseCode, String courseName, int counted, int present) {
    }

    /** One counted (finalized, not cancelled) lecture in a student's history. */
    public record HistoryRow(long lectureId, String courseCode, int lectureNo, String topic, Instant startsAt,
                             AttendanceStatus status, boolean corrected) {
    }

    /** One finalized lecture in a course report. */
    public record LectureTotals(long lectureId, int lectureNo, String topic, Instant startsAt, int present, int total) {
    }

    /** One student's totals in a course report. */
    public record StudentTotals(long studentId, String name, String enrollmentNo, int counted, int present) {
    }

    private static final String SESSION_SELECT = """
            SELECT s.*, l.course_id, c.code AS course_code, l.lecture_no, l.topic
            FROM attendance_sessions s
            JOIN lectures l ON l.id = s.lecture_id
            JOIN courses c ON c.id = l.course_id
            """;

    public Instant dbNow(Connection con) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT clock_timestamp()"); ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getObject(1, OffsetDateTime.class).toInstant();
        }
    }

    // ------------------------------------------------------------------ sessions

    /** Opens a session; the code expires `seconds` after database now. The partial unique index allows one OPEN per lecture. */
    public long insertSession(Connection con, long lectureId, long professorId, String codeHash, int seconds) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("""
                INSERT INTO attendance_sessions (lecture_id, started_by, code_hash, started_at, code_expires_at)
                SELECT ?, ?, ?, t.now, t.now + make_interval(secs => ?)
                FROM (SELECT clock_timestamp() AS now) t
                RETURNING id""")) {
            ps.setLong(1, lectureId);
            ps.setLong(2, professorId);
            ps.setString(3, codeHash);
            ps.setInt(4, seconds);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    public void rotateCode(Connection con, long sessionId, String codeHash, int seconds) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "UPDATE attendance_sessions SET code_hash = ?, code_expires_at = clock_timestamp() + make_interval(secs => ?) WHERE id = ?")) {
            ps.setString(1, codeHash);
            ps.setInt(2, seconds);
            ps.setLong(3, sessionId);
            ps.executeUpdate();
        }
    }

    public void closeSession(Connection con, long sessionId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "UPDATE attendance_sessions SET status = 'CLOSED', closed_at = clock_timestamp() WHERE id = ? AND status = 'OPEN'")) {
            ps.setLong(1, sessionId);
            ps.executeUpdate();
        }
    }

    public int closeOpenSessions(Connection con, long lectureId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "UPDATE attendance_sessions SET status = 'CLOSED', closed_at = clock_timestamp() WHERE lecture_id = ? AND status = 'OPEN'")) {
            ps.setLong(1, lectureId);
            return ps.executeUpdate();
        }
    }

    public Optional<AttendanceSession> findSession(Connection con, long id) throws SQLException {
        return oneSession(con, SESSION_SELECT + " WHERE s.id = ?", id);
    }

    /** Shared lock: many students can submit at once; closing or rotating (FOR UPDATE) waits for them. */
    public Optional<AttendanceSession> lockSessionShared(Connection con, long id) throws SQLException {
        return oneSession(con, SESSION_SELECT + " WHERE s.id = ? FOR SHARE OF s", id);
    }

    public Optional<AttendanceSession> lockSession(Connection con, long id) throws SQLException {
        return oneSession(con, SESSION_SELECT + " WHERE s.id = ? FOR UPDATE OF s", id);
    }

    /** Code hash of a session — only ever compared, never returned to the UI. */
    public String codeHash(Connection con, long sessionId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT code_hash FROM attendance_sessions WHERE id = ?")) {
            ps.setLong(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    public Optional<AttendanceSession> openSessionForLecture(Connection con, long lectureId) throws SQLException {
        return oneSession(con, SESSION_SELECT + " WHERE s.lecture_id = ? AND s.status = 'OPEN'", lectureId);
    }

    /** Open sessions in the given courses for lectures the student has no record for yet. */
    public List<AttendanceSession> openSessions(Connection con, List<Long> courseIds, long studentId) throws SQLException {
        if (courseIds.isEmpty()) {
            return List.of();
        }
        try (PreparedStatement ps = con.prepareStatement(SESSION_SELECT
                + " WHERE s.status = 'OPEN' AND l.course_id = ANY(?) AND l.attendance_finalized_at IS NULL"
                + " AND NOT EXISTS (SELECT 1 FROM attendance a WHERE a.lecture_id = l.id AND a.student_id = ?)"
                + " ORDER BY s.started_at")) {
            ps.setArray(1, con.createArrayOf("bigint", courseIds.toArray()));
            ps.setLong(2, studentId);
            return mapSessions(ps);
        }
    }

    // ------------------------------------------------------------------ records

    /** Inserts PRESENT; returns false if the student already has a record (primary key). */
    public boolean markPresent(Connection con, long lectureId, long studentId, long sessionId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("""
                INSERT INTO attendance (lecture_id, student_id, status, source, session_id, marked_at)
                VALUES (?, ?, 'PRESENT', 'CODE', ?, clock_timestamp())
                ON CONFLICT (lecture_id, student_id) DO NOTHING""")) {
            ps.setLong(1, lectureId);
            ps.setLong(2, studentId);
            ps.setLong(3, sessionId);
            return ps.executeUpdate() == 1;
        }
    }

    public Optional<AttendanceStatus> status(Connection con, long lectureId, long studentId, boolean lock) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT status FROM attendance WHERE lecture_id = ? AND student_id = ?" + (lock ? " FOR UPDATE" : ""))) {
            ps.setLong(1, lectureId);
            ps.setLong(2, studentId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(AttendanceStatus.valueOf(rs.getString(1))) : Optional.empty();
            }
        }
    }

    /** Adds one wrong attempt and returns the new total. */
    public int recordFailure(Connection con, long sessionId, long studentId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("""
                INSERT INTO attendance_code_failures (session_id, student_id, failures, last_at)
                VALUES (?, ?, 1, clock_timestamp())
                ON CONFLICT (session_id, student_id)
                DO UPDATE SET failures = attendance_code_failures.failures + 1, last_at = clock_timestamp()
                RETURNING failures""")) {
            ps.setLong(1, sessionId);
            ps.setLong(2, studentId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    public int failures(Connection con, long sessionId, long studentId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT failures FROM attendance_code_failures WHERE session_id = ? AND student_id = ?")) {
            ps.setLong(1, sessionId);
            ps.setLong(2, studentId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /** Finalization: every currently enrolled student without a record becomes ABSENT. Returns rows added. */
    public int insertAbsentees(Connection con, long lectureId, long courseId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("""
                INSERT INTO attendance (lecture_id, student_id, status, source, marked_at)
                SELECT ?, e.student_id, 'ABSENT', 'FINALIZE', clock_timestamp()
                FROM enrollments e
                WHERE e.course_id = ?
                ON CONFLICT (lecture_id, student_id) DO NOTHING""")) {
            ps.setLong(1, lectureId);
            ps.setLong(2, courseId);
            return ps.executeUpdate();
        }
    }

    public void setFinalized(Connection con, long lectureId, long professorId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "UPDATE lectures SET attendance_finalized_at = clock_timestamp(), attendance_finalized_by = ?, updated_at = now() WHERE id = ?")) {
            ps.setLong(1, professorId);
            ps.setLong(2, lectureId);
            ps.executeUpdate();
        }
    }

    public void updateStatus(Connection con, long lectureId, long studentId, AttendanceStatus status) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "UPDATE attendance SET status = ?, source = 'CORRECTION', marked_at = clock_timestamp() WHERE lecture_id = ? AND student_id = ?")) {
            ps.setString(1, status.name());
            ps.setLong(2, lectureId);
            ps.setLong(3, studentId);
            ps.executeUpdate();
        }
    }

    public void insertCorrection(Connection con, long lectureId, long studentId, AttendanceStatus oldStatus,
                                 AttendanceStatus newStatus, String reason, long professorId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("""
                INSERT INTO attendance_corrections (lecture_id, student_id, old_status, new_status, reason, corrected_by)
                VALUES (?, ?, ?, ?, ?, ?)""")) {
            ps.setLong(1, lectureId);
            ps.setLong(2, studentId);
            ps.setString(3, oldStatus.name());
            ps.setString(4, newStatus.name());
            ps.setString(5, reason);
            ps.setLong(6, professorId);
            ps.executeUpdate();
        }
    }

    /** Everyone enrolled now, plus anyone who has a record (e.g. unenrolled later), with their status. */
    public List<AttendanceRecord> roster(Connection con, long lectureId, long courseId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("""
                SELECT u.id, u.full_name, s.enrollment_no, a.status, a.source, a.marked_at
                FROM users u
                JOIN students s ON s.user_id = u.id
                LEFT JOIN attendance a ON a.student_id = u.id AND a.lecture_id = ?
                WHERE a.student_id IS NOT NULL
                   OR EXISTS (SELECT 1 FROM enrollments e WHERE e.course_id = ? AND e.student_id = u.id)
                ORDER BY u.full_name""")) {
            ps.setLong(1, lectureId);
            ps.setLong(2, courseId);
            List<AttendanceRecord> list = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String status = rs.getString("status");
                    OffsetDateTime marked = rs.getObject("marked_at", OffsetDateTime.class);
                    list.add(new AttendanceRecord(rs.getLong("id"), rs.getString("full_name"), rs.getString("enrollment_no"),
                            status == null ? null : AttendanceStatus.valueOf(status), rs.getString("source"),
                            marked == null ? null : marked.toInstant()));
                }
            }
            return list;
        }
    }

    public List<AttendanceCorrection> corrections(Connection con, long lectureId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("""
                SELECT k.*, st.full_name AS student_name, p.full_name AS professor_name
                FROM attendance_corrections k
                JOIN users st ON st.id = k.student_id
                JOIN users p ON p.id = k.corrected_by
                WHERE k.lecture_id = ?
                ORDER BY k.corrected_at DESC, k.id DESC""")) {
            ps.setLong(1, lectureId);
            List<AttendanceCorrection> list = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new AttendanceCorrection(rs.getLong("id"), rs.getLong("lecture_id"), rs.getLong("student_id"),
                            rs.getString("student_name"), AttendanceStatus.valueOf(rs.getString("old_status")),
                            AttendanceStatus.valueOf(rs.getString("new_status")), rs.getString("reason"),
                            rs.getString("professor_name"), rs.getObject("corrected_at", OffsetDateTime.class).toInstant()));
                }
            }
            return list;
        }
    }

    // ------------------------------------------------------------------ reports

    /**
     * Counted lectures = attendance finalized AND not cancelled. A student's denominator is the number of
     * counted lectures in which they have a record (i.e. they were enrolled when attendance was finalized).
     */
    public List<CourseTotals> studentTotals(Connection con, long studentId, List<Long> courseIds) throws SQLException {
        if (courseIds.isEmpty()) {
            return List.of();
        }
        try (PreparedStatement ps = con.prepareStatement("""
                SELECT c.id, c.code, c.name,
                       count(a.lecture_id) AS counted,
                       count(a.lecture_id) FILTER (WHERE a.status = 'PRESENT') AS present
                FROM courses c
                LEFT JOIN lectures l ON l.course_id = c.id
                     AND l.attendance_finalized_at IS NOT NULL AND l.status <> 'CANCELLED'
                LEFT JOIN attendance a ON a.lecture_id = l.id AND a.student_id = ?
                WHERE c.id = ANY(?)
                GROUP BY c.id, c.code, c.name
                ORDER BY c.code""")) {
            ps.setLong(1, studentId);
            ps.setArray(2, con.createArrayOf("bigint", courseIds.toArray()));
            List<CourseTotals> list = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new CourseTotals(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getInt(5)));
                }
            }
            return list;
        }
    }

    public List<HistoryRow> history(Connection con, long studentId, List<Long> courseIds, int limit) throws SQLException {
        if (courseIds.isEmpty()) {
            return List.of();
        }
        try (PreparedStatement ps = con.prepareStatement("""
                SELECT l.id, c.code, l.lecture_no, l.topic, l.starts_at, a.status,
                       EXISTS (SELECT 1 FROM attendance_corrections k WHERE k.lecture_id = l.id AND k.student_id = a.student_id) AS corrected
                FROM attendance a
                JOIN lectures l ON l.id = a.lecture_id
                JOIN courses c ON c.id = l.course_id
                WHERE a.student_id = ? AND l.course_id = ANY(?)
                  AND l.attendance_finalized_at IS NOT NULL AND l.status <> 'CANCELLED'
                ORDER BY l.starts_at DESC
                LIMIT ?""")) {
            ps.setLong(1, studentId);
            ps.setArray(2, con.createArrayOf("bigint", courseIds.toArray()));
            ps.setInt(3, limit);
            List<HistoryRow> list = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new HistoryRow(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getString(4),
                            rs.getObject(5, OffsetDateTime.class).toInstant(), AttendanceStatus.valueOf(rs.getString(6)),
                            rs.getBoolean(7)));
                }
            }
            return list;
        }
    }

    public List<LectureTotals> lectureTotals(Connection con, long courseId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("""
                SELECT l.id, l.lecture_no, l.topic, l.starts_at,
                       count(a.student_id) FILTER (WHERE a.status = 'PRESENT') AS present,
                       count(a.student_id) AS total
                FROM lectures l
                LEFT JOIN attendance a ON a.lecture_id = l.id
                WHERE l.course_id = ? AND l.attendance_finalized_at IS NOT NULL AND l.status <> 'CANCELLED'
                GROUP BY l.id
                ORDER BY l.starts_at""")) {
            ps.setLong(1, courseId);
            List<LectureTotals> list = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new LectureTotals(rs.getLong(1), rs.getInt(2), rs.getString(3),
                            rs.getObject(4, OffsetDateTime.class).toInstant(), rs.getInt(5), rs.getInt(6)));
                }
            }
            return list;
        }
    }

    /** Totals for every currently enrolled student of a course. */
    public List<StudentTotals> studentTotalsForCourse(Connection con, long courseId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("""
                SELECT u.id, u.full_name, s.enrollment_no,
                       count(a.lecture_id) AS counted,
                       count(a.lecture_id) FILTER (WHERE a.status = 'PRESENT') AS present
                FROM enrollments e
                JOIN users u ON u.id = e.student_id
                JOIN students s ON s.user_id = u.id
                LEFT JOIN lectures l ON l.course_id = e.course_id
                     AND l.attendance_finalized_at IS NOT NULL AND l.status <> 'CANCELLED'
                LEFT JOIN attendance a ON a.lecture_id = l.id AND a.student_id = e.student_id
                WHERE e.course_id = ?
                GROUP BY u.id, u.full_name, s.enrollment_no
                ORDER BY u.full_name""")) {
            ps.setLong(1, courseId);
            List<StudentTotals> list = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new StudentTotals(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getInt(5)));
                }
            }
            return list;
        }
    }

    /** Lectures that have started, are not cancelled or drafts, and still await finalization. */
    public int awaitingFinalization(Connection con, long courseId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("""
                SELECT count(*) FROM lectures
                WHERE course_id = ? AND attendance_finalized_at IS NULL
                  AND status IN ('PUBLISHED', 'COMPLETED') AND starts_at <= clock_timestamp()""")) {
            ps.setLong(1, courseId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    public int presentCount(Connection con, long lectureId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT count(*) FROM attendance WHERE lecture_id = ? AND status = 'PRESENT'")) {
            ps.setLong(1, lectureId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    // ------------------------------------------------------------------ mapping

    private Optional<AttendanceSession> oneSession(Connection con, String sql, long id) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, id);
            List<AttendanceSession> list = mapSessions(ps);
            return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
        }
    }

    private List<AttendanceSession> mapSessions(PreparedStatement ps) throws SQLException {
        List<AttendanceSession> list = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                OffsetDateTime closed = rs.getObject("closed_at", OffsetDateTime.class);
                list.add(new AttendanceSession(rs.getLong("id"), rs.getLong("lecture_id"), rs.getLong("course_id"),
                        rs.getString("course_code"), rs.getInt("lecture_no"), rs.getString("topic"),
                        "OPEN".equals(rs.getString("status")),
                        rs.getObject("code_expires_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("started_at", OffsetDateTime.class).toInstant(),
                        closed == null ? null : closed.toInstant()));
            }
        }
        return list;
    }

}
