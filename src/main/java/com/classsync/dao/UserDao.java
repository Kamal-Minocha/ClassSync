package com.classsync.dao;

import com.classsync.model.Role;
import com.classsync.model.StudentProfile;
import com.classsync.model.User;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * JDBC access to users / students / professors.
 *
 * This is the "first DAO example": it shows INSERT (with generated keys), SELECT with
 * ResultSet mapping, UPDATE and DELETE, all with PreparedStatement and try-with-resources.
 * Every method receives the Connection from the service so the service decides the
 * transaction boundary.
 */
public class UserDao {

    /** A user plus the stored password hash — only ever used inside AuthService. */
    public record Credentials(User user, String passwordHash) {
    }

    private static final String USER_COLUMNS =
            "u.id, u.email, u.full_name, u.role, u.active, u.theme, u.created_at, u.last_login_at";

    // ---------- INSERT ----------

    public long insertUser(Connection con, String email, String fullName, Role role, String passwordHash)
            throws SQLException {
        String sql = "INSERT INTO users (email, full_name, role, password_hash) VALUES (?, ?, ?, ?)";
        try (PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, email);
            ps.setString(2, fullName);
            ps.setString(3, role.name());
            ps.setString(4, passwordHash);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong("id");
            }
        }
    }

    public void insertStudent(Connection con, long userId, String enrollmentNo, String department, int semester)
            throws SQLException {
        String sql = "INSERT INTO students (user_id, enrollment_no, department, semester) VALUES (?, ?, ?, ?)";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setString(2, enrollmentNo);
            ps.setString(3, department);
            ps.setInt(4, semester);
            ps.executeUpdate();
        }
    }

    public void insertProfessor(Connection con, long userId, String employeeNo, String department,
                                String designation) throws SQLException {
        String sql = "INSERT INTO professors (user_id, employee_no, department, designation) VALUES (?, ?, ?, ?)";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setString(2, employeeNo);
            ps.setString(3, department);
            ps.setString(4, designation);
            ps.executeUpdate();
        }
    }

    // ---------- SELECT ----------

    public Optional<User> findById(Connection con, long id) throws SQLException {
        String sql = "SELECT " + USER_COLUMNS + " FROM users u WHERE u.id = ?";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapUser(rs)) : Optional.empty();
            }
        }
    }

    public Optional<Credentials> findCredentialsByEmail(Connection con, String email) throws SQLException {
        String sql = "SELECT " + USER_COLUMNS + ", u.password_hash FROM users u WHERE lower(u.email) = lower(?)";
        return findCredentials(con, sql, email);
    }

    public Optional<Credentials> findCredentialsByEnrollmentNo(Connection con, String enrollmentNo)
            throws SQLException {
        String sql = "SELECT " + USER_COLUMNS + ", u.password_hash FROM users u "
                + "JOIN students s ON s.user_id = u.id WHERE s.enrollment_no = ?";
        return findCredentials(con, sql, enrollmentNo);
    }

    private Optional<Credentials> findCredentials(Connection con, String sql, String value) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, value);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new Credentials(mapUser(rs), rs.getString("password_hash")));
            }
        }
    }

    public boolean emailExists(Connection con, String email) throws SQLException {
        return exists(con, "SELECT 1 FROM users WHERE lower(email) = lower(?)", email);
    }

    public boolean enrollmentNoExists(Connection con, String enrollmentNo) throws SQLException {
        return exists(con, "SELECT 1 FROM students WHERE enrollment_no = ?", enrollmentNo);
    }

    private boolean exists(Connection con, String sql, String value) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, value);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public Optional<StudentProfile> findStudentProfile(Connection con, long userId) throws SQLException {
        String sql = "SELECT user_id, enrollment_no, department, semester FROM students WHERE user_id = ?";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new StudentProfile(rs.getLong("user_id"), rs.getString("enrollment_no"),
                        rs.getString("department"), rs.getInt("semester")));
            }
        }
    }

    // ---------- UPDATE ----------

    public void updateLastLogin(Connection con, long userId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("UPDATE users SET last_login_at = now() WHERE id = ?")) {
            ps.setLong(1, userId);
            ps.executeUpdate();
        }
    }

    /** @return number of rows changed (0 when the user does not exist) */
    /** Replaces the password hash of the account with this email (any role). Returns rows changed. */
    public int updatePasswordHash(Connection con, String email, String passwordHash) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "UPDATE users SET password_hash = ? WHERE lower(email) = lower(?)")) {
            ps.setString(1, passwordHash);
            ps.setString(2, email);
            return ps.executeUpdate();
        }
    }

    /** Activates or deactivates the account with this email. Returns rows changed. */
    public int updateActive(Connection con, String email, boolean active) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("UPDATE users SET active = ? WHERE lower(email) = lower(?)")) {
            ps.setBoolean(1, active);
            ps.setString(2, email);
            return ps.executeUpdate();
        }
    }

    public int updateTheme(Connection con, long userId, String theme) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("UPDATE users SET theme = ? WHERE id = ?")) {
            ps.setString(1, theme);
            ps.setLong(2, userId);
            return ps.executeUpdate();
        }
    }

    public int updateFullName(Connection con, long userId, String fullName) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("UPDATE users SET full_name = ? WHERE id = ?")) {
            ps.setString(1, fullName);
            ps.setLong(2, userId);
            return ps.executeUpdate();
        }
    }

    // ---------- DELETE ----------

    /**
     * Deletes an account. The application normally deactivates accounts instead; foreign keys
     * with ON DELETE RESTRICT make this fail for users who already have academic history.
     */
    public int delete(Connection con, long userId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("DELETE FROM users WHERE id = ?")) {
            ps.setLong(1, userId);
            return ps.executeUpdate();
        }
    }

    // ---------- mapping ----------

    static User mapUser(ResultSet rs) throws SQLException {
        OffsetDateTime lastLogin = rs.getObject("last_login_at", OffsetDateTime.class);
        return new User(
                rs.getLong("id"),
                rs.getString("email"),
                rs.getString("full_name"),
                Role.valueOf(rs.getString("role")),
                rs.getBoolean("active"),
                rs.getString("theme"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                lastLogin == null ? null : lastLogin.toInstant());
    }
}
