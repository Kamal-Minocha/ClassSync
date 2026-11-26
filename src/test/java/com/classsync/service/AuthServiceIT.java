package com.classsync.service;

import com.classsync.TestDb;
import com.classsync.config.Database;
import com.classsync.model.Role;
import com.classsync.model.User;
import com.classsync.service.AppException.AuthenticationException;
import com.classsync.service.AppException.ValidationException;
import com.classsync.util.PasswordHasher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class AuthServiceIT {

    private AuthService auth;
    private Database db;

    @BeforeEach
    void setUp() {
        db = TestDb.reset();
        auth = new AuthService(db, new PasswordHasher(10_000), Pattern.compile("^[a-z0-9._-]+@college\\.edu$"));
    }

    private AuthService.Registration reg(String email, String enrollment, String pw, String confirm) {
        return new AuthService.Registration("Asha Rao", email, enrollment, "Computer Science", 3,
                pw.toCharArray(), confirm.toCharArray());
    }

    @Test
    void registrationCreatesStudentOnlyAndLoginWorksByEmailOrEnrollment() throws SQLException {
        User u = auth.registerStudent(reg("Asha@College.edu", "cs2024001", "secret123", "secret123"));
        assertEquals(Role.STUDENT, u.role(), "registration can never pick a privileged role");
        assertEquals("asha@college.edu", u.email(), "email normalised to lower case");

        assertEquals(u.id(), auth.login("asha@college.edu", "secret123".toCharArray()).id());
        assertEquals(u.id(), auth.login("CS2024001", "secret123".toCharArray()).id());
        assertEquals(u.id(), auth.login("cs2024001", "secret123".toCharArray()).id(), "enrollment is case-insensitive");
    }

    @Test
    void passwordIsStoredAsPbkdf2HashNotPlainText() throws SQLException {
        auth.registerStudent(reg("hash@college.edu", "CS2024010", "secret123", "secret123"));
        try (Connection con = db.open(); var st = con.createStatement();
             var rs = st.executeQuery("SELECT password_hash FROM users WHERE email = 'hash@college.edu'")) {
            rs.next();
            String stored = rs.getString(1);
            assertTrue(stored.startsWith("pbkdf2_sha256$"));
            assertFalse(stored.contains("secret123"));
        }
    }

    @Test
    void wrongPasswordAndUnknownUserGiveTheSameMessage() throws SQLException {
        auth.registerStudent(reg("a@college.edu", "CS2024002", "secret123", "secret123"));
        AuthenticationException wrong = assertThrows(AuthenticationException.class,
                () -> auth.login("a@college.edu", "wrongpass1".toCharArray()));
        AuthenticationException unknown = assertThrows(AuthenticationException.class,
                () -> auth.login("nobody@college.edu", "secret123".toCharArray()));
        assertEquals(wrong.getMessage(), unknown.getMessage());
    }

    @Test
    void duplicateEmailOrEnrollmentIsRejected() throws SQLException {
        auth.registerStudent(reg("dup@college.edu", "CS2024003", "secret123", "secret123"));
        assertThrows(ValidationException.class,
                () -> auth.registerStudent(reg("DUP@college.edu", "CS2024004", "secret123", "secret123")));
        assertThrows(ValidationException.class,
                () -> auth.registerStudent(reg("other@college.edu", "CS2024003", "secret123", "secret123")));
    }

    @Test
    void registrationValidation() {
        assertThrows(ValidationException.class,
                () -> auth.registerStudent(reg("asha@gmail.com", "CS2024005", "secret123", "secret123")), "non-college email");
        assertThrows(ValidationException.class,
                () -> auth.registerStudent(reg("b@college.edu", "CS-1", "secret123", "secret123")), "bad enrollment");
        assertThrows(ValidationException.class,
                () -> auth.registerStudent(reg("b@college.edu", "CS2024005", "short1", "short1")), "short password");
        assertThrows(ValidationException.class,
                () -> auth.registerStudent(reg("b@college.edu", "CS2024005", "onlyletters", "onlyletters")), "no digit");
        assertThrows(ValidationException.class,
                () -> auth.registerStudent(reg("b@college.edu", "CS2024005", "secret123", "secret124")), "mismatch");
    }

    @Test
    void deactivatedAccountCannotSignIn() throws SQLException {
        User u = auth.registerStudent(reg("gone@college.edu", "CS2024006", "secret123", "secret123"));
        try (Connection con = db.open(); var ps = con.prepareStatement("UPDATE users SET active = false WHERE id = ?")) {
            ps.setLong(1, u.id());
            ps.executeUpdate();
        }
        AuthenticationException e = assertThrows(AuthenticationException.class,
                () -> auth.login("gone@college.edu", "secret123".toCharArray()));
        assertTrue(e.getMessage().contains("deactivated"));
    }

    @Test
    void provisionedRolesSignIn() throws SQLException {
        auth.provisionProfessor("Dr. Meera Iyer", "meera@college.edu", "EMP101", "CS", "Professor", "prof1234".toCharArray());
        auth.provisionLearner(Role.CR, "Ravi Kumar", "ravi@college.edu", "CS2024007", "CS", 3, "ravi1234".toCharArray());
        assertEquals(Role.PROFESSOR, auth.login("meera@college.edu", "prof1234".toCharArray()).role());
        assertEquals(Role.CR, auth.login("CS2024007", "ravi1234".toCharArray()).role());
    }

    @Test
    void loginClearsThePasswordArray() throws SQLException {
        auth.registerStudent(reg("clear@college.edu", "CS2024008", "secret123", "secret123"));
        char[] pw = "secret123".toCharArray();
        auth.login("clear@college.edu", pw);
        for (char c : pw) {
            assertEquals('\0', c);
        }
    }

    @Test
    void resetPasswordReplacesTheOldOneForAnyRole() throws Exception {
        auth.provisionProfessor("Dr. Meera Iyer", "meera@college.edu", "EMP101", "CS", "Professor", "OldPass123".toCharArray());
        assertThrows(AppException.ValidationException.class,
                () -> auth.resetPassword("meera@college.edu", "short".toCharArray()), "password policy applies");
        auth.resetPassword("MEERA@college.edu", "NewPass456".toCharArray());   // email is case-insensitive
        assertThrows(AppException.AuthenticationException.class, () -> auth.login("meera@college.edu", "OldPass123".toCharArray()));
        assertEquals("meera@college.edu", auth.login("meera@college.edu", "NewPass456".toCharArray()).email());
        assertThrows(AppException.NotFoundException.class, () -> auth.resetPassword("nobody@college.edu", "NewPass456".toCharArray()));
    }

    @Test
    void deactivatedAccountsCannotSignInUntilReactivated() throws Exception {
        auth.provisionLearner(com.classsync.model.Role.STUDENT, "Asha Rao", "asha@college.edu", "CS2024001", "CS", 3,
                "Secret123".toCharArray());
        auth.setActive("asha@college.edu", false);
        assertThrows(AppException.AuthenticationException.class, () -> auth.login("asha@college.edu", "Secret123".toCharArray()));
        auth.setActive("asha@college.edu", true);
        assertEquals("asha@college.edu", auth.login("CS2024001", "Secret123".toCharArray()).email());
        assertThrows(AppException.NotFoundException.class, () -> auth.setActive("nobody@college.edu", false));
    }
}
