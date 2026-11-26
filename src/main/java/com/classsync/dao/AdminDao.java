package com.classsync.dao;

import com.classsync.model.Role;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Queries and updates behind the admin user-management dashboard. */
public class AdminDao {

    /** One account with its role-specific profile (student or professor columns may be null). */
    public record UserRow(long id, String email, String fullName, Role role, boolean active, Instant createdAt,
                          Instant lastLoginAt, String enrollmentNo, String employeeNo, String department,
                          Integer semester, String designation, int coursesEnrolled, int coursesTaught) {

        /** Enrollment number for learners, employee number for professors, empty for admins. */
        public String identifier() {
            return enrollmentNo != null ? enrollmentNo : employeeNo != null ? employeeNo : "";
        }
    }

    /** Listing filter; null role = all roles, null text = no search. */
    public record Filter(Role role, String text, boolean includeDisabled, int limit) {
    }

    /** An entry of the recent account-administration log. */
    public record AuditRow(String actorName, String action, String details, Instant at) {
    }

    private static final String SELECT = """
            SELECT u.id, u.email, u.full_name, u.role, u.active, u.created_at, u.last_login_at,
                   s.enrollment_no, s.semester, p.employee_no, p.designation,
                   coalesce(s.department, p.department) AS department,
                   (SELECT count(*) FROM enrollments e WHERE e.student_id = u.id) AS courses_enrolled,
                   (SELECT count(*) FROM courses c WHERE c.professor_id = u.id) AS courses_taught
            FROM users u
            LEFT JOIN students s ON s.user_id = u.id
            LEFT JOIN professors p ON p.user_id = u.id
            """;

    public List<UserRow> list(Connection con, Filter f) throws SQLException {
        String q = f.text() == null || f.text().isBlank() ? null : "%" + LectureDao.escapeLike(f.text().trim()) + "%";
        try (PreparedStatement ps = con.prepareStatement(SELECT + """
                 WHERE (?::text IS NULL OR u.role = ?)
                   AND (? OR u.active)
                   AND (?::text IS NULL OR u.full_name ILIKE ? OR u.email ILIKE ? OR s.enrollment_no ILIKE ? OR p.employee_no ILIKE ?)
                 ORDER BY u.full_name
                 LIMIT ?""")) {
            String role = f.role() == null ? null : f.role().name();
            ps.setString(1, role);
            ps.setString(2, role);
            ps.setBoolean(3, f.includeDisabled());
            for (int i = 4; i <= 8; i++) {
                ps.setString(i, q);
            }
            ps.setInt(9, f.limit());
            return map(ps);
        }
    }

    public java.util.Optional<UserRow> find(Connection con, long userId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(SELECT + " WHERE u.id = ?")) {
            ps.setLong(1, userId);
            List<UserRow> rows = map(ps);
            return rows.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(rows.get(0));
        }
    }

    /** Lock one user row while changing it (role/active/password). */
    public java.util.Optional<UserRow> lock(Connection con, long userId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT id FROM users WHERE id = ? FOR UPDATE")) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return java.util.Optional.empty();
                }
            }
        }
        return find(con, userId);
    }

    public List<UserRow> recent(Connection con, String orderColumn, int limit) throws SQLException {
        // orderColumn is chosen by the service from two fixed values, never from user input
        String order = "last".equals(orderColumn) ? "u.last_login_at DESC NULLS LAST" : "u.created_at DESC";
        String where = "last".equals(orderColumn) ? " WHERE u.last_login_at IS NOT NULL" : "";
        try (PreparedStatement ps = con.prepareStatement(SELECT + where + " ORDER BY " + order + " LIMIT ?")) {
            ps.setInt(1, limit);
            return map(ps);
        }
    }

    /** Active accounts per role. */
    public Map<Role, Integer> activeCounts(Connection con) throws SQLException {
        Map<Role, Integer> map = new EnumMap<>(Role.class);
        for (Role r : Role.values()) {
            map.put(r, 0);
        }
        try (PreparedStatement ps = con.prepareStatement("SELECT role, count(*) FROM users WHERE active GROUP BY role");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                map.put(Role.valueOf(rs.getString(1)), rs.getInt(2));
            }
        }
        return map;
    }

    public int disabledCount(Connection con) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT count(*) FROM users WHERE NOT active");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    /**
     * Locks every admin row. Taken before disabling an admin so that two admins disabling each other at the
     * same moment run one after the other instead of both seeing "2 active admins" and leaving none.
     */
    public void lockAdmins(Connection con) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT id FROM users WHERE role = 'ADMIN' ORDER BY id FOR UPDATE");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                rs.getLong(1);
            }
        }
    }

    public int activeAdmins(Connection con) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT count(*) FROM users WHERE role = 'ADMIN' AND active");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    public void setActive(Connection con, long userId, boolean active) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("UPDATE users SET active = ? WHERE id = ?")) {
            ps.setBoolean(1, active);
            ps.setLong(2, userId);
            ps.executeUpdate();
        }
    }

    public void setRole(Connection con, long userId, Role role) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("UPDATE users SET role = ? WHERE id = ?")) {
            ps.setString(1, role.name());
            ps.setLong(2, userId);
            ps.executeUpdate();
        }
    }

    /** A CR turned back into a student loses every course staff delegation. Returns rows removed. */
    public int removeStaffDelegations(Connection con, long userId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("DELETE FROM course_staff WHERE user_id = ?")) {
            ps.setLong(1, userId);
            return ps.executeUpdate();
        }
    }

    public void setPasswordHash(Connection con, long userId, String hash) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("UPDATE users SET password_hash = ? WHERE id = ?")) {
            ps.setString(1, hash);
            ps.setLong(2, userId);
            ps.executeUpdate();
        }
    }

    public void updateName(Connection con, long userId, String fullName) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("UPDATE users SET full_name = ? WHERE id = ?")) {
            ps.setString(1, fullName);
            ps.setLong(2, userId);
            ps.executeUpdate();
        }
    }

    public void updateStudent(Connection con, long userId, String department, int semester) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("UPDATE students SET department = ?, semester = ? WHERE user_id = ?")) {
            ps.setString(1, department);
            ps.setInt(2, semester);
            ps.setLong(3, userId);
            ps.executeUpdate();
        }
    }

    public void updateProfessor(Connection con, long userId, String department, String designation) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("UPDATE professors SET department = ?, designation = ? WHERE user_id = ?")) {
            ps.setString(1, department);
            ps.setString(2, designation);
            ps.setLong(3, userId);
            ps.executeUpdate();
        }
    }

    /** Recent account-administration actions (provisioning, admin changes, password resets). */
    public List<AuditRow> recentAdminActions(Connection con, int limit) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("""
                SELECT coalesce(u.full_name, 'Provisioning tool'), a.action, a.details, a.created_at
                FROM audit_logs a LEFT JOIN users u ON u.id = a.actor_id
                WHERE a.action LIKE 'ADMIN\\_%' OR a.action IN ('PROVISION', 'PASSWORD_RESET', 'ACCOUNT_ACTIVATE', 'ACCOUNT_DEACTIVATE')
                ORDER BY a.created_at DESC, a.id DESC LIMIT ?""")) {
            ps.setInt(1, limit);
            List<AuditRow> list = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new AuditRow(rs.getString(1), rs.getString(2), rs.getString(3),
                            rs.getObject(4, OffsetDateTime.class).toInstant()));
                }
            }
            return list;
        }
    }

    private List<UserRow> map(PreparedStatement ps) throws SQLException {
        List<UserRow> list = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                OffsetDateTime last = rs.getObject("last_login_at", OffsetDateTime.class);
                int sem = rs.getInt("semester");
                Integer semester = rs.wasNull() ? null : sem;
                list.add(new UserRow(rs.getLong("id"), rs.getString("email"), rs.getString("full_name"),
                        Role.valueOf(rs.getString("role")), rs.getBoolean("active"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant(), last == null ? null : last.toInstant(),
                        rs.getString("enrollment_no"), rs.getString("employee_no"), rs.getString("department"), semester,
                        rs.getString("designation"), rs.getInt("courses_enrolled"), rs.getInt("courses_taught")));
            }
        }
        return list;
    }
}
