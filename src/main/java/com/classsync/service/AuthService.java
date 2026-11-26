package com.classsync.service;

import com.classsync.config.Database;
import com.classsync.config.Tx;
import com.classsync.dao.AuditDao;
import com.classsync.dao.UserDao;
import com.classsync.model.Role;
import com.classsync.model.User;
import com.classsync.service.AppException.AuthenticationException;
import com.classsync.service.AppException.ValidationException;
import com.classsync.util.PasswordHasher;
import com.classsync.util.Validation;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Registration, login and account provisioning.
 *
 * Registration from the UI can only ever create a STUDENT. Professor and CR accounts are
 * created by the local Provision tool (com.classsync.tools.Provision) or demo data.
 */
public class AuthService {

    public static final String INVALID_LOGIN = "Invalid email/enrollment number or password.";
    private static final Pattern ENROLLMENT = Pattern.compile("^[A-Z0-9]{6,15}$");

    public record Registration(String fullName, String email, String enrollmentNo, String department,
                               int semester, char[] password, char[] confirmPassword) {
    }

    private final Database db;
    private final PasswordHasher hasher;
    private final Pattern emailPattern;
    private final UserDao users = new UserDao();
    private final AuditDao audit = new AuditDao();
    private volatile String dummyHash;

    public AuthService(Database db, PasswordHasher hasher, Pattern emailPattern) {
        this.db = db;
        this.hasher = hasher;
        this.emailPattern = emailPattern;
    }

    /**
     * Signs in with a college email or an enrollment number.
     * Unknown account and wrong password produce the same message (no account enumeration).
     */
    public User login(String identifier, char[] password) throws SQLException {
        String id = identifier == null ? "" : identifier.trim();
        if (id.isEmpty() || password == null || password.length == 0) {
            throw new ValidationException("Enter your email or enrollment number and your password.");
        }
        try (Connection con = db.open()) {
            Optional<UserDao.Credentials> found = id.contains("@")
                    ? users.findCredentialsByEmail(con, id)
                    : users.findCredentialsByEnrollmentNo(con, id.toUpperCase(Locale.ROOT));
            if (found.isEmpty()) {
                // spend the same hashing time as a real check so timing does not reveal accounts
                hasher.verify(password, dummyHash());
                throw new AuthenticationException(INVALID_LOGIN);
            }
            if (!hasher.verify(password, found.get().passwordHash())) {
                throw new AuthenticationException(INVALID_LOGIN);
            }
            User user = found.get().user();
            if (!user.active()) {
                throw new AuthenticationException("This account has been deactivated. Contact your department.");
            }
            users.updateLastLogin(con, user.id());
            return user;
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    /** Creates a STUDENT account. The role is fixed here; the form cannot choose it. */
    public User registerStudent(Registration r) throws SQLException {
        String name = Validation.text(r.fullName(), "Full name", 2, 100);
        String email = Validation.text(r.email(), "College email", 3, 120).toLowerCase(Locale.ROOT);
        if (!emailPattern.matcher(email).matches()) {
            throw new ValidationException("Use your college email address (for example name@college.edu).");
        }
        String enrollment = Validation.text(r.enrollmentNo(), "Enrollment number", 1, 15).toUpperCase(Locale.ROOT);
        if (!ENROLLMENT.matcher(enrollment).matches()) {
            throw new ValidationException("Enrollment number must be 6–15 letters or digits.");
        }
        String department = Validation.text(r.department(), "Department", 2, 80);
        int semester = Validation.range(r.semester(), "Semester", 1, 12);
        Validation.password(r.password());
        if (!Arrays.equals(r.password(), r.confirmPassword())) {
            throw new ValidationException("Passwords do not match.");
        }
        String hash = hasher.hash(r.password());
        Arrays.fill(r.password(), '\0');
        Arrays.fill(r.confirmPassword(), '\0');

        try {
            return Tx.inTransaction(db, con -> {
                if (users.emailExists(con, email)) {
                    throw new ValidationException("An account with this email already exists.");
                }
                if (users.enrollmentNoExists(con, enrollment)) {
                    throw new ValidationException("An account with this enrollment number already exists.");
                }
                long id = users.insertUser(con, email, name, Role.STUDENT, hash);
                users.insertStudent(con, id, enrollment, department, semester);
                audit.log(con, id, "REGISTER", "user", id, "student self-registration");
                return users.findById(con, id).orElseThrow();
            });
        } catch (SQLException e) {
            if ("23505".equals(e.getSQLState())) {
                // two registrations raced: the unique index is the final defence
                throw new ValidationException("An account with this email or enrollment number already exists.");
            }
            throw e;
        }
    }

    /** Provisioning (local tool / demo data only): professor account. */
    public long provisionProfessor(String fullName, String email, String employeeNo, String department,
                                   String designation, char[] password) throws SQLException {
        Validation.password(password);
        String hash = hasher.hash(password);
        return Tx.inTransaction(db, con -> {
            long id = users.insertUser(con, email.toLowerCase(Locale.ROOT), fullName, Role.PROFESSOR, hash);
            users.insertProfessor(con, id, employeeNo, department, designation);
            audit.log(con, null, "PROVISION", "user", id, "professor account");
            return id;
        });
    }

    /** Provisioning (local tool / demo data only): the first ADMIN account (admins then manage users in the app). */
    public long provisionAdmin(String fullName, String email, char[] password) throws SQLException {
        Validation.password(password);
        String hash = hasher.hash(password);
        return Tx.inTransaction(db, con -> {
            long id = users.insertUser(con, email.toLowerCase(Locale.ROOT), fullName, Role.ADMIN, hash);
            audit.log(con, null, "PROVISION", "user", id, "admin account");
            return id;
        });
    }

    /** Provisioning (local tool / demo data only): STUDENT or CR account. */
    public long provisionLearner(Role role, String fullName, String email, String enrollmentNo, String department,
                                 int semester, char[] password) throws SQLException {
        if (!role.isLearner()) {
            throw new IllegalArgumentException("Learner role required");
        }
        Validation.password(password);
        String hash = hasher.hash(password);
        return Tx.inTransaction(db, con -> {
            long id = users.insertUser(con, email.toLowerCase(Locale.ROOT), fullName, role, hash);
            users.insertStudent(con, id, enrollmentNo.toUpperCase(Locale.ROOT), department, semester);
            audit.log(con, null, "PROVISION", "user", id, role + " account");
            return id;
        });
    }

    /**
     * Provisioning tool only (migration-owner credentials): sets a new password for a forgotten one.
     * The old password is not needed and cannot be recovered — only its PBKDF2 hash was ever stored.
     */
    public void resetPassword(String email, char[] newPassword) throws SQLException {
        Validation.password(newPassword);
        String hash = hasher.hash(newPassword);
        java.util.Arrays.fill(newPassword, '\0');
        Tx.inTransaction(db, con -> {
            if (users.updatePasswordHash(con, email, hash) != 1) {
                throw new AppException.NotFoundException("No account with email " + email + ".");
            }
            audit.log(con, null, "PASSWORD_RESET", "user", null, "by provisioning tool");
            return null;
        });
    }

    /** Provisioning tool only: deactivated accounts cannot sign in; all their records are kept. */
    public void setActive(String email, boolean active) throws SQLException {
        Tx.inTransaction(db, con -> {
            if (users.updateActive(con, email, active) != 1) {
                throw new AppException.NotFoundException("No account with email " + email + ".");
            }
            audit.log(con, null, active ? "ACCOUNT_ACTIVATE" : "ACCOUNT_DEACTIVATE", "user", null, "by provisioning tool");
            return null;
        });
    }

    public void saveTheme(User actor, boolean dark) throws SQLException {
        try (Connection con = db.open()) {
            users.updateTheme(con, actor.id(), dark ? "DARK" : "LIGHT");
        }
    }

    public Optional<com.classsync.model.StudentProfile> studentProfile(User actor) throws SQLException {
        try (Connection con = db.open()) {
            return users.findStudentProfile(con, actor.id());
        }
    }

    private String dummyHash() {
        if (dummyHash == null) {
            dummyHash = hasher.hash("not-a-real-password-1".toCharArray());
        }
        return dummyHash;
    }
}
