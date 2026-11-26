package com.classsync.dao;

import com.classsync.model.Course;
import com.classsync.model.CoursePermission;
import com.classsync.model.EnrolledStudent;
import com.classsync.model.Role;
import com.classsync.model.StaffMember;
import com.classsync.model.Topic;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Courses, enrollments, CR staff delegation and syllabus topics. */
public class CourseDao {

    private static final String COURSE_SELECT = """
            SELECT c.id, c.code, c.name, c.department, c.semester, c.description, c.professor_id,
                   u.full_name AS professor_name, c.archived_at,
                   (SELECT count(*) FROM enrollments e WHERE e.course_id = c.id) AS enrolled_count
            FROM courses c JOIN users u ON u.id = c.professor_id
            """;

    // ---------------------------------------------------------------- courses

    public long insert(Connection con, String code, String name, String department, int semester,
                       String description, long professorId) throws SQLException {
        String sql = "INSERT INTO courses (code, name, department, semester, description, professor_id) VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, code);
            ps.setString(2, name);
            ps.setString(3, department);
            ps.setInt(4, semester);
            ps.setString(5, description);
            ps.setLong(6, professorId);
            ps.executeUpdate();
            try (ResultSet k = ps.getGeneratedKeys()) {
                k.next();
                return k.getLong(1);
            }
        }
    }

    public void update(Connection con, long id, String name, String department, int semester, String description)
            throws SQLException {
        String sql = "UPDATE courses SET name = ?, department = ?, semester = ?, description = ? WHERE id = ?";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, name);
            ps.setString(2, department);
            ps.setInt(3, semester);
            ps.setString(4, description);
            ps.setLong(5, id);
            ps.executeUpdate();
        }
    }

    public void setArchived(Connection con, long id, boolean archived) throws SQLException {
        String sql = "UPDATE courses SET archived_at = " + (archived ? "now()" : "NULL") + " WHERE id = ?";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    public Optional<Course> findById(Connection con, long id) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(COURSE_SELECT + " WHERE c.id = ?")) {
            ps.setLong(1, id);
            List<Course> list = mapCourses(ps);
            return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
        }
    }

    public List<Course> listForProfessor(Connection con, long professorId, boolean includeArchived) throws SQLException {
        String sql = COURSE_SELECT + " WHERE c.professor_id = ?"
                + (includeArchived ? "" : " AND c.archived_at IS NULL") + " ORDER BY c.archived_at NULLS FIRST, c.code";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, professorId);
            return mapCourses(ps);
        }
    }

    public List<Course> listForStudent(Connection con, long studentId, boolean includeArchived) throws SQLException {
        String sql = COURSE_SELECT + " JOIN enrollments en ON en.course_id = c.id WHERE en.student_id = ?"
                + (includeArchived ? "" : " AND c.archived_at IS NULL") + " ORDER BY c.code";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, studentId);
            return mapCourses(ps);
        }
    }

    private List<Course> mapCourses(PreparedStatement ps) throws SQLException {
        List<Course> list = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                OffsetDateTime archived = rs.getObject("archived_at", OffsetDateTime.class);
                list.add(new Course(rs.getLong("id"), rs.getString("code"), rs.getString("name"),
                        rs.getString("department"), rs.getInt("semester"), rs.getString("description"),
                        rs.getLong("professor_id"), rs.getString("professor_name"),
                        archived == null ? null : archived.toInstant(), rs.getInt("enrolled_count")));
            }
        }
        return list;
    }

    // ---------------------------------------------------------------- access lookups

    public boolean isProfessorOf(Connection con, long userId, long courseId) throws SQLException {
        return exists(con, "SELECT 1 FROM courses WHERE id = ? AND professor_id = ?", courseId, userId);
    }

    public boolean isEnrolled(Connection con, long userId, long courseId) throws SQLException {
        return exists(con, "SELECT 1 FROM enrollments WHERE course_id = ? AND student_id = ?", courseId, userId);
    }

    /** Staff permissions of a CR in a course; empty when the user is not staff there. */
    public Optional<Set<CoursePermission>> staffPermissions(Connection con, long userId, long courseId)
            throws SQLException {
        String sql = """
                SELECT cs.* FROM course_staff cs JOIN users u ON u.id = cs.user_id
                WHERE cs.course_id = ? AND cs.user_id = ? AND u.role = 'CR' AND u.active""";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, courseId);
            ps.setLong(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(permissions(rs)) : Optional.empty();
            }
        }
    }

    private static Set<CoursePermission> permissions(ResultSet rs) throws SQLException {
        Set<CoursePermission> set = EnumSet.of(CoursePermission.VIEW_SCHEDULE, CoursePermission.UPLOAD_MATERIALS);
        for (CoursePermission p : CoursePermission.values()) {
            if (p.delegable() && rs.getBoolean(p.column())) {
                set.add(p);
            }
        }
        return set;
    }

    /** Course ids the user can see: taught (professor) or enrolled (student/CR). */
    public List<Long> accessibleCourseIds(Connection con, long userId, Role role) throws SQLException {
        String sql = role == Role.PROFESSOR
                ? "SELECT id FROM courses WHERE professor_id = ? AND archived_at IS NULL"
                : "SELECT e.course_id FROM enrollments e JOIN courses c ON c.id = e.course_id WHERE e.student_id = ? AND c.archived_at IS NULL";
        List<Long> ids = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getLong(1));
                }
            }
        }
        return ids;
    }

    private boolean exists(Connection con, String sql, long a, long b) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, a);
            ps.setLong(2, b);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    // ---------------------------------------------------------------- enrollments

    public Optional<EnrolledStudent> findStudentByEnrollmentNo(Connection con, String enrollmentNo) throws SQLException {
        String sql = """
                SELECT u.id, u.full_name, u.email, s.enrollment_no, u.role
                FROM students s JOIN users u ON u.id = s.user_id WHERE s.enrollment_no = ? AND u.active""";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, enrollmentNo);
            List<EnrolledStudent> list = mapStudents(ps);
            return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
        }
    }

    public void enroll(Connection con, long courseId, long studentId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("INSERT INTO enrollments (course_id, student_id) VALUES (?, ?)")) {
            ps.setLong(1, courseId);
            ps.setLong(2, studentId);
            ps.executeUpdate();
        }
    }

    public int unenroll(Connection con, long courseId, long studentId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("DELETE FROM enrollments WHERE course_id = ? AND student_id = ?")) {
            ps.setLong(1, courseId);
            ps.setLong(2, studentId);
            return ps.executeUpdate();
        }
    }

    public List<EnrolledStudent> listEnrolled(Connection con, long courseId) throws SQLException {
        String sql = """
                SELECT u.id, u.full_name, u.email, s.enrollment_no, u.role
                FROM enrollments e JOIN students s ON s.user_id = e.student_id JOIN users u ON u.id = s.user_id
                WHERE e.course_id = ? ORDER BY u.full_name""";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, courseId);
            return mapStudents(ps);
        }
    }

    private List<EnrolledStudent> mapStudents(PreparedStatement ps) throws SQLException {
        List<EnrolledStudent> list = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(new EnrolledStudent(rs.getLong("id"), rs.getString("full_name"), rs.getString("email"),
                        rs.getString("enrollment_no"), Role.valueOf(rs.getString("role"))));
            }
        }
        return list;
    }

    // ---------------------------------------------------------------- CR staff

    /** Insert or replace the delegated permissions of a CR in a course. */
    public void upsertStaff(Connection con, long courseId, long userId, Set<CoursePermission> perms, long grantedBy)
            throws SQLException {
        String sql = """
                INSERT INTO course_staff (course_id, user_id, can_update_lectures, can_review_notes,
                    can_publish_announcements, can_organize_resources, can_moderate_discussions, granted_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (course_id, user_id) DO UPDATE SET
                    can_update_lectures = EXCLUDED.can_update_lectures,
                    can_review_notes = EXCLUDED.can_review_notes,
                    can_publish_announcements = EXCLUDED.can_publish_announcements,
                    can_organize_resources = EXCLUDED.can_organize_resources,
                    can_moderate_discussions = EXCLUDED.can_moderate_discussions,
                    granted_by = EXCLUDED.granted_by, granted_at = now()""";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, courseId);
            ps.setLong(2, userId);
            ps.setBoolean(3, perms.contains(CoursePermission.UPDATE_LECTURES));
            ps.setBoolean(4, perms.contains(CoursePermission.REVIEW_NOTES));
            ps.setBoolean(5, perms.contains(CoursePermission.PUBLISH_ANNOUNCEMENTS));
            ps.setBoolean(6, perms.contains(CoursePermission.ORGANIZE_RESOURCES));
            ps.setBoolean(7, perms.contains(CoursePermission.MODERATE_DISCUSSIONS));
            ps.setLong(8, grantedBy);
            ps.executeUpdate();
        }
    }

    public int removeStaff(Connection con, long courseId, long userId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("DELETE FROM course_staff WHERE course_id = ? AND user_id = ?")) {
            ps.setLong(1, courseId);
            ps.setLong(2, userId);
            return ps.executeUpdate();
        }
    }

    public List<StaffMember> listStaff(Connection con, long courseId) throws SQLException {
        String sql = """
                SELECT cs.*, u.full_name, s.enrollment_no FROM course_staff cs
                JOIN users u ON u.id = cs.user_id JOIN students s ON s.user_id = cs.user_id
                WHERE cs.course_id = ? ORDER BY u.full_name""";
        List<StaffMember> list = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, courseId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new StaffMember(courseId, rs.getLong("user_id"), rs.getString("full_name"),
                            rs.getString("enrollment_no"), permissions(rs)));
                }
            }
        }
        return list;
    }

    /** Courses in which the CR holds a staff row. */
    public List<Course> listStaffedCourses(Connection con, long userId) throws SQLException {
        String sql = COURSE_SELECT + " JOIN course_staff cs ON cs.course_id = c.id WHERE cs.user_id = ? AND c.archived_at IS NULL ORDER BY c.code";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, userId);
            return mapCourses(ps);
        }
    }

    // ---------------------------------------------------------------- topics

    public long insertTopic(Connection con, long courseId, int unitNo, String name, int sequence) throws SQLException {
        String sql = "INSERT INTO topics (course_id, unit_no, name, sequence) VALUES (?, ?, ?, ?)";
        try (PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, courseId);
            ps.setInt(2, unitNo);
            ps.setString(3, name);
            ps.setInt(4, sequence);
            ps.executeUpdate();
            try (ResultSet k = ps.getGeneratedKeys()) {
                k.next();
                return k.getLong(1);
            }
        }
    }

    public List<Topic> listTopics(Connection con, long courseId) throws SQLException {
        String sql = "SELECT id, course_id, unit_no, name, sequence FROM topics WHERE course_id = ? ORDER BY unit_no, sequence, id";
        List<Topic> list = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, courseId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new Topic(rs.getLong("id"), rs.getLong("course_id"), rs.getInt("unit_no"),
                            rs.getString("name"), rs.getInt("sequence")));
                }
            }
        }
        return list;
    }

    /** Students and CRs currently enrolled: the denominator for participation figures. */
    public int enrolledCount(Connection con, long courseId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT count(*) FROM enrollments WHERE course_id = ?")) {
            ps.setLong(1, courseId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }
}
