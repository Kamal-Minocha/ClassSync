package com.classsync.service;

import com.classsync.Fixtures;
import com.classsync.TestDb;
import com.classsync.config.AppConfig;
import com.classsync.config.Database;
import com.classsync.dao.UserDao;
import com.classsync.model.CoursePermission;
import com.classsync.model.Role;
import com.classsync.model.User;
import com.classsync.service.AdminService.NewUser;
import com.classsync.service.AppException.AccessDeniedException;
import com.classsync.service.AppException.AuthenticationException;
import com.classsync.service.AppException.ValidationException;
import com.classsync.util.PasswordHasher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.EnumSet;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Admin user management against real PostgreSQL. */
class AdminServiceIT {

    private Database db;
    private Fixtures fx;
    private AdminService admin;
    private AuthService auth;
    private User root;
    private User prof;
    private User student;
    private User cr;

    @BeforeEach
    void setUp() throws SQLException {
        db = TestDb.reset();
        fx = new Fixtures(db);
        PasswordHasher hasher = new PasswordHasher(10_000);
        admin = new AdminService(db, hasher);
        auth = new AuthService(db, hasher, Pattern.compile("^[a-z0-9._-]+@college\\.edu$"));
        root = user(auth.provisionAdmin("Root Admin", "root@college.edu", "Admin#2026".toCharArray()));
        prof = fx.professor("Prof");
        student = fx.student("Student");
        cr = fx.cr("CR");
    }

    private User user(long id) throws SQLException {
        try (Connection con = db.open()) {
            return new UserDao().findById(con, id).orElseThrow();
        }
    }

    private static NewUser newUser(Role role, String email, String id) {
        return new NewUser(role, "New " + role.label(), email, id, id, "Computer Science", 4, "Lecturer",
                "Welcome#2026".toCharArray());
    }

    private long count(String sql, Object... args) throws SQLException {
        try (Connection con = db.open(); PreparedStatement ps = con.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    @Test
    void onlyActiveAdminsCanManageUsers() {
        for (User u : new User[]{student, cr, prof}) {
            assertThrows(AccessDeniedException.class, () -> admin.overview(u));
            assertThrows(AccessDeniedException.class, () -> admin.list(u, null, null, true));
            assertThrows(AccessDeniedException.class, () -> admin.create(u, newUser(Role.PROFESSOR, "x@college.edu", "EMP900")));
            assertThrows(AccessDeniedException.class, () -> admin.setActive(u, student.id(), false));
        }
    }

    @Test
    void createsEveryKindOfAccountAndEachCanSignIn() throws SQLException {
        long s = admin.create(root, newUser(Role.STUDENT, "new.student@college.edu", "CS2025001"));
        long c = admin.create(root, newUser(Role.CR, "new.cr@college.edu", "CS2025002"));
        long p = admin.create(root, newUser(Role.PROFESSOR, "new.prof@college.edu", "EMP900"));
        long a = admin.create(root, newUser(Role.ADMIN, "new.admin@college.edu", null));
        assertEquals(Role.STUDENT, auth.login("CS2025001", "Welcome#2026".toCharArray()).role());
        assertEquals(Role.CR, auth.login("new.cr@college.edu", "Welcome#2026".toCharArray()).role());
        assertEquals(Role.PROFESSOR, auth.login("new.prof@college.edu", "Welcome#2026".toCharArray()).role());
        assertEquals(Role.ADMIN, auth.login("NEW.ADMIN@college.edu", "Welcome#2026".toCharArray()).role());
        assertEquals(2, count("SELECT count(*) FROM students WHERE user_id IN (?, ?)", s, c));
        assertEquals(1, count("SELECT count(*) FROM professors WHERE user_id = ? AND designation = 'Lecturer'", p));
        assertEquals(0, count("SELECT count(*) FROM students WHERE user_id = ?", a), "admins have no learner profile");
        assertEquals(4, count("SELECT count(*) FROM audit_logs WHERE action = 'ADMIN_CREATE_USER' AND actor_id = ?", root.id()));
        assertEquals(0, count("SELECT count(*) FROM audit_logs WHERE details LIKE '%Welcome#2026%'"), "passwords never logged");
    }

    @Test
    void creationIsValidated() throws SQLException {
        assertThrows(ValidationException.class, () -> admin.create(root, newUser(Role.STUDENT, "bad-email", "CS2025001")));
        assertThrows(ValidationException.class, () -> admin.create(root, newUser(Role.STUDENT, "a@college.edu", "cs-1")), "enrollment format");
        assertThrows(ValidationException.class, () -> admin.create(root, new NewUser(Role.PROFESSOR, "P", "p@college.edu", null,
                "EMP1", "CS", null, null, "short".toCharArray())), "name and password rules");
        admin.create(root, newUser(Role.STUDENT, "dup@college.edu", "CS2025009"));
        assertThrows(ValidationException.class, () -> admin.create(root, newUser(Role.CR, "DUP@college.edu", "CS2025010")), "email taken");
        assertThrows(ValidationException.class, () -> admin.create(root, newUser(Role.CR, "other@college.edu", "cs2025009")), "enrollment taken");
    }

    @Test
    void roleSwitchIsStudentCrOnlyAndDemotionRemovesCourseRights() throws SQLException {
        long course = fx.course(prof, "CS301");
        fx.enroll(course, student, cr);
        fx.staff(course, cr, prof, EnumSet.of(CoursePermission.REVIEW_NOTES));
        admin.changeRole(root, student.id(), Role.CR);
        assertEquals(Role.CR, user(student.id()).role());
        admin.changeRole(root, cr.id(), Role.STUDENT);
        assertEquals(Role.STUDENT, user(cr.id()).role());
        assertEquals(0, count("SELECT count(*) FROM course_staff WHERE user_id = ?", cr.id()), "delegations removed with the CR role");
        assertThrows(ValidationException.class, () -> admin.changeRole(root, prof.id(), Role.STUDENT));
        assertThrows(ValidationException.class, () -> admin.changeRole(root, student.id(), Role.PROFESSOR));
    }

    @Test
    void resetPasswordAndDisableWork() throws SQLException {
        long id = admin.create(root, newUser(Role.STUDENT, "reset.me@college.edu", "CS2025020"));
        admin.resetPassword(root, id, "Changed#99".toCharArray());
        assertThrows(AuthenticationException.class, () -> auth.login("reset.me@college.edu", "Welcome#2026".toCharArray()));
        assertEquals(id, auth.login("reset.me@college.edu", "Changed#99".toCharArray()).id());
        admin.setActive(root, id, false);
        assertThrows(AuthenticationException.class, () -> auth.login("reset.me@college.edu", "Changed#99".toCharArray()));
        assertEquals(1, count("SELECT count(*) FROM users WHERE id = ?", id), "disabled, not deleted");
        admin.setActive(root, id, true);
        assertEquals(id, auth.login("reset.me@college.edu", "Changed#99".toCharArray()).id());
    }

    @Test
    void adminsCannotDisableThemselvesAndADisabledAdminIsRefusedAtOnce() throws SQLException {
        ValidationException self = assertThrows(ValidationException.class, () -> admin.setActive(root, root.id(), false));
        assertTrue(self.getMessage().contains("own"));
        User second = user(admin.create(root, newUser(Role.ADMIN, "second@college.edu", null)));
        admin.setActive(root, second.id(), false);
        assertThrows(AccessDeniedException.class, () -> admin.list(second, null, null, false));
        assertThrows(AccessDeniedException.class, () -> admin.setActive(second, root.id(), false));
    }

    /** Two admins try to disable each other at the same moment (10 rounds): at least one admin always remains. */
    @Test
    void mutualDisablingRaceAlwaysLeavesAnActiveAdmin() throws Exception {
        User second = user(admin.create(root, newUser(Role.ADMIN, "second@college.edu", null)));
        for (int round = 0; round < 10; round++) {
            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
            java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
            var a = pool.submit(() -> { go.await(); try { admin.setActive(root, second.id(), false); } catch (AppException e) { } return null; });
            var b = pool.submit(() -> { go.await(); try { admin.setActive(second, root.id(), false); } catch (AppException e) { } return null; });
            go.countDown();
            a.get();
            b.get();
            pool.shutdown();
            assertEquals(1, count("SELECT count(*) FROM users WHERE role = 'ADMIN' AND active"), "round " + round);
            // reset for the next round
            try (Connection con = db.open(); var st = con.createStatement()) {
                st.executeUpdate("UPDATE users SET active = TRUE WHERE role = 'ADMIN'");
            }
        }
    }

    @Test
    void listFiltersAndOverviewCounts() throws SQLException {
        admin.setActive(root, student.id(), false);
        assertEquals(1, admin.list(root, Role.PROFESSOR, null, false).size());
        assertTrue(admin.list(root, Role.STUDENT, null, false).isEmpty(), "disabled hidden by default");
        assertEquals(1, admin.list(root, Role.STUDENT, null, true).size());
        assertEquals(1, admin.list(root, null, "root@", true).size(), "search by email");
        assertTrue(admin.list(root, null, "%", true).isEmpty(), "% is matched literally, not as a wildcard");
        var o = admin.overview(root);
        assertEquals(1, o.activeByRole().get(Role.ADMIN));
        assertEquals(1, o.activeByRole().get(Role.PROFESSOR));
        assertEquals(1, o.disabled());
        assertFalse(o.recentActions().isEmpty());
    }

    @Test
    void worksThroughTheRestrictedApplicationRole() throws SQLException {
        AppConfig cfg = TestDb.config();
        String appPassword = cfg.get(AppConfig.APP_PASSWORD);
        org.junit.jupiter.api.Assumptions.assumeTrue(appPassword != null, "app password not configured");
        Database appDb = new Database(cfg.require(AppConfig.TEST_DB_URL), cfg.require(AppConfig.APP_USER), appPassword);
        AdminService asApp = new AdminService(appDb, new PasswordHasher(10_000));
        long id = asApp.create(root, newUser(Role.PROFESSOR, "via.app@college.edu", "EMP777"));
        asApp.resetPassword(root, id, "Another#1".toCharArray());
        asApp.setActive(root, id, false);
        assertEquals(1, asApp.overview(root).disabled());
    }
}
