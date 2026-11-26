package com.classsync.service;

import com.classsync.config.Database;
import com.classsync.config.Tx;
import com.classsync.dao.AdminDao;
import com.classsync.dao.AuditDao;
import com.classsync.dao.UserDao;
import com.classsync.model.Role;
import com.classsync.model.User;
import com.classsync.service.AppException.AccessDeniedException;
import com.classsync.service.AppException.NotFoundException;
import com.classsync.service.AppException.ValidationException;
import com.classsync.util.PasswordHasher;
import com.classsync.util.Validation;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Admin user management: create every kind of account, edit details, switch STUDENT ↔ CR, reset passwords,
 * disable / re-enable accounts.
 *
 * Every call first re-reads the caller from the database and requires an ACTIVE ADMIN, so a removed or disabled
 * admin is refused on the next action. Safety rules: admins cannot disable or demote themselves, and the last
 * active admin can never be disabled. Accounts are disabled, never deleted, so academic history is kept.
 * Each change is written to the audit log with the acting admin; passwords are never logged.
 */
public class AdminService {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern ENROLLMENT = Pattern.compile("^[A-Z0-9]{6,15}$");
    /** Letters and digits without look-alikes (0/O, 1/l/I) for generated passwords. */
    private static final String PASSWORD_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789#@%";

    /** Data for a new account. Fields that do not apply to the role are ignored. */
    public record NewUser(Role role, String fullName, String email, String enrollmentNo, String employeeNo,
                          String department, Integer semester, String designation, char[] password) {
    }

    /** Editable profile details (email and role-defining numbers stay fixed). */
    public record Details(String fullName, String department, Integer semester, String designation) {
    }

    /** Admin dashboard data. */
    public record Overview(Map<Role, Integer> activeByRole, int disabled, List<AdminDao.UserRow> newest,
                           List<AdminDao.UserRow> recentSignIns, List<AdminDao.AuditRow> recentActions) {
    }

    private final Database db;
    private final PasswordHasher hasher;
    private final SecureRandom random = new SecureRandom();
    private final AdminDao dao = new AdminDao();
    private final UserDao users = new UserDao();
    private final AuditDao audit = new AuditDao();

    public AdminService(Database db, PasswordHasher hasher) {
        this.db = db;
        this.hasher = hasher;
    }

    // ---------------------------------------------------------------- reading

    public Overview overview(User admin) throws SQLException {
        try (Connection con = db.open()) {
            requireAdmin(con, admin);
            return new Overview(dao.activeCounts(con), dao.disabledCount(con), dao.recent(con, "created", 6),
                    dao.recent(con, "last", 6), dao.recentAdminActions(con, 8));
        }
    }

    public List<AdminDao.UserRow> list(User admin, Role role, String text, boolean includeDisabled) throws SQLException {
        String q = text == null ? null : Validation.optional(text, "Search", 100);
        try (Connection con = db.open()) {
            requireAdmin(con, admin);
            return dao.list(con, new AdminDao.Filter(role, q, includeDisabled, 1000));
        }
    }

    // ---------------------------------------------------------------- changes

    /** Creates any kind of account. Returns the new user id. */
    public long create(User admin, NewUser n) throws SQLException {
        if (n.role() == null) {
            throw new ValidationException("Choose the account type.");
        }
        String name = Validation.text(n.fullName(), "Full name", 2, 100);
        String email = Validation.text(n.email(), "Email", 5, 120).toLowerCase(Locale.ROOT);
        if (!EMAIL.matcher(email).matches()) {
            throw new ValidationException("Enter a valid email address.");
        }
        Validation.password(n.password());
        String enrollment = null;
        String employee = null;
        String department = null;
        int semester = 0;
        String designation = null;
        if (n.role().isLearner()) {
            enrollment = Validation.text(n.enrollmentNo(), "Enrollment number", 6, 15).toUpperCase(Locale.ROOT);
            if (!ENROLLMENT.matcher(enrollment).matches()) {
                throw new ValidationException("Enrollment number must be 6–15 letters or digits.");
            }
            department = Validation.text(n.department(), "Department", 2, 80);
            semester = Validation.range(n.semester() == null ? 0 : n.semester(), "Semester", 1, 12);
        } else if (n.role() == Role.PROFESSOR) {
            employee = Validation.text(n.employeeNo(), "Employee number", 2, 15).toUpperCase(Locale.ROOT);
            department = Validation.text(n.department(), "Department", 2, 80);
            designation = n.designation() == null || n.designation().isBlank() ? "Professor"
                    : Validation.text(n.designation(), "Designation", 2, 60);
        }
        String hash = hasher.hash(n.password());
        Arrays.fill(n.password(), '\0');
        final String fEnrollment = enrollment;
        final String fEmployee = employee;
        final String fDepartment = department;
        final int fSemester = semester;
        final String fDesignation = designation;
        try {
            return Tx.inTransaction(db, con -> {
                requireAdmin(con, admin);
                long id = users.insertUser(con, email, name, n.role(), hash);
                if (n.role().isLearner()) {
                    users.insertStudent(con, id, fEnrollment, fDepartment, fSemester);
                } else if (n.role() == Role.PROFESSOR) {
                    users.insertProfessor(con, id, fEmployee, fDepartment, fDesignation);
                }
                audit.log(con, admin.id(), "ADMIN_CREATE_USER", "user", id, n.role() + " " + email);
                return id;
            });
        } catch (SQLException e) {
            if ("23505".equals(e.getSQLState())) {
                throw new ValidationException("An account with this email, enrollment number or employee number already exists.");
            }
            throw e;
        }
    }

    public void updateDetails(User admin, long userId, Details d) throws SQLException {
        String name = Validation.text(d.fullName(), "Full name", 2, 100);
        Tx.inTransaction(db, con -> {
            requireAdmin(con, admin);
            AdminDao.UserRow u = dao.lock(con, userId).orElseThrow(() -> new NotFoundException("Account not found."));
            dao.updateName(con, userId, name);
            if (u.role().isLearner()) {
                dao.updateStudent(con, userId, Validation.text(d.department(), "Department", 2, 80),
                        Validation.range(d.semester() == null ? 0 : d.semester(), "Semester", 1, 12));
            } else if (u.role() == Role.PROFESSOR) {
                String designation = d.designation() == null || d.designation().isBlank() ? "Professor"
                        : Validation.text(d.designation(), "Designation", 2, 60);
                dao.updateProfessor(con, userId, Validation.text(d.department(), "Department", 2, 80), designation);
            }
            audit.log(con, admin.id(), "ADMIN_UPDATE_USER", "user", userId, "");
            return null;
        });
    }

    /**
     * Switches a learner between STUDENT and CR. Turning a CR back into a student also removes every course
     * staff delegation in the same transaction. Professors and admins keep their role (different profiles).
     */
    public void changeRole(User admin, long userId, Role role) throws SQLException {
        if (role == null || !role.isLearner()) {
            throw new ValidationException("Only Student ↔ Class Representative can be switched.");
        }
        Tx.inTransaction(db, con -> {
            requireAdmin(con, admin);
            AdminDao.UserRow u = dao.lock(con, userId).orElseThrow(() -> new NotFoundException("Account not found."));
            if (!u.role().isLearner()) {
                throw new ValidationException("Only students and class representatives can switch roles.");
            }
            if (u.role() == role) {
                return null;
            }
            dao.setRole(con, userId, role);
            int removed = role == Role.STUDENT ? dao.removeStaffDelegations(con, userId) : 0;
            audit.log(con, admin.id(), "ADMIN_CHANGE_ROLE", "user", userId, u.role() + " -> " + role
                    + (removed > 0 ? ", " + removed + " course delegation(s) removed" : ""));
            return null;
        });
    }

    /** Sets a new password for any account (the old one is not needed and cannot be recovered). */
    public void resetPassword(User admin, long userId, char[] password) throws SQLException {
        Validation.password(password);
        String hash = hasher.hash(password);
        Arrays.fill(password, '\0');
        Tx.inTransaction(db, con -> {
            requireAdmin(con, admin);
            dao.lock(con, userId).orElseThrow(() -> new NotFoundException("Account not found."));
            dao.setPasswordHash(con, userId, hash);
            audit.log(con, admin.id(), "ADMIN_RESET_PASSWORD", "user", userId, "");
            return null;
        });
    }

    /** Disables or re-enables an account. Never yourself; never the last active admin. */
    public void setActive(User admin, long userId, boolean active) throws SQLException {
        Tx.inTransaction(db, con -> {
            requireAdmin(con, admin);
            AdminDao.UserRow peek = dao.find(con, userId).orElseThrow(() -> new NotFoundException("Account not found."));
            if (!active && peek.role() == Role.ADMIN) {
                // serialise admin deactivations (all admin rows, fixed order → no deadlock), then re-check the caller
                dao.lockAdmins(con);
                requireAdmin(con, admin);
            }
            AdminDao.UserRow u = dao.lock(con, userId).orElseThrow(() -> new NotFoundException("Account not found."));
            if (!active && userId == admin.id()) {
                throw new ValidationException("You cannot disable your own account.");
            }
            if (!active && u.role() == Role.ADMIN && u.active() && dao.activeAdmins(con) <= 1) {
                throw new ValidationException("This is the last active administrator and cannot be disabled.");
            }
            if (u.active() == active) {
                return null;
            }
            dao.setActive(con, userId, active);
            audit.log(con, admin.id(), active ? "ADMIN_ENABLE_USER" : "ADMIN_DISABLE_USER", "user", userId, "");
            return null;
        });
    }

    /** A random 12-character password (letters, digits, symbols) that satisfies the password policy. */
    public String generatePassword() {
        while (true) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 12; i++) {
                sb.append(PASSWORD_CHARS.charAt(random.nextInt(PASSWORD_CHARS.length())));
            }
            String p = sb.toString();
            if (p.chars().anyMatch(Character::isLetter) && p.chars().anyMatch(Character::isDigit)) {
                return p;
            }
        }
    }

    /** Fresh database check: the caller must still be an ACTIVE ADMIN. */
    private void requireAdmin(Connection con, User admin) throws SQLException {
        User current = users.findById(con, admin.id()).orElse(null);
        if (current == null || current.role() != Role.ADMIN || !current.active()) {
            throw new AccessDeniedException("Only an active administrator can manage user accounts.");
        }
    }
}
