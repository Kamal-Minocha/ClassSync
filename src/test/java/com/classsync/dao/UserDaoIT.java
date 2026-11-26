package com.classsync.dao;

import com.classsync.TestDb;
import com.classsync.config.Database;
import com.classsync.config.Tx;
import com.classsync.model.Role;
import com.classsync.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

/** Real PostgreSQL: CRUD, constraints and transaction rollback for the first DAO. */
class UserDaoIT {

    private Database db;
    private final UserDao dao = new UserDao();

    @BeforeEach
    void setUp() {
        db = TestDb.reset();
    }

    @Test
    void insertSelectUpdateDelete() throws SQLException {
        try (Connection con = db.open()) {
            long id = dao.insertUser(con, "asha@college.edu", "Asha Rao", Role.STUDENT, "hash");
            dao.insertStudent(con, id, "CS2024001", "Computer Science", 3);

            User found = dao.findById(con, id).orElseThrow();
            assertEquals("Asha Rao", found.fullName());
            assertEquals(Role.STUDENT, found.role());
            assertTrue(found.active());
            assertEquals("LIGHT", found.theme());

            assertTrue(dao.findCredentialsByEmail(con, "ASHA@college.edu").isPresent(), "email lookup is case-insensitive");
            assertEquals(id, dao.findCredentialsByEnrollmentNo(con, "CS2024001").orElseThrow().user().id());

            assertEquals(1, dao.updateFullName(con, id, "Asha R."));
            assertEquals(1, dao.updateTheme(con, id, "DARK"));
            assertEquals("DARK", dao.findById(con, id).orElseThrow().theme());

            assertEquals(1, dao.delete(con, id));
            assertTrue(dao.findById(con, id).isEmpty());
            assertTrue(dao.findStudentProfile(con, id).isEmpty(), "student row cascades with user");
        }
    }

    @Test
    void duplicateEmailIgnoringCaseIsRejected() throws SQLException {
        try (Connection con = db.open()) {
            dao.insertUser(con, "dup@college.edu", "One", Role.STUDENT, "h");
            SQLException e = assertThrows(SQLException.class,
                    () -> dao.insertUser(con, "DUP@college.edu", "Two", Role.STUDENT, "h"));
            assertEquals("23505", e.getSQLState(), "unique_violation");
        }
    }

    @Test
    void invalidRoleAndEnrollmentFormatAreRejectedByCheckConstraints() throws SQLException {
        try (Connection con = db.open()) {
            SQLException badRole = assertThrows(SQLException.class, () -> {
                try (var st = con.createStatement()) {
                    st.execute("INSERT INTO users (email, full_name, role, password_hash) "
                            + "VALUES ('x@college.edu','X','SUPERUSER','h')");
                }
            });
            assertEquals("23514", badRole.getSQLState(), "check_violation");

            long id = dao.insertUser(con, "y@college.edu", "Y", Role.STUDENT, "h");
            SQLException badEnrollment = assertThrows(SQLException.class,
                    () -> dao.insertStudent(con, id, "bad no!", "CS", 1));
            assertEquals("23514", badEnrollment.getSQLState());
        }
    }

    @Test
    void transactionRollsBackEveryStatementOnFailure() throws SQLException {
        // user insert succeeds, student insert violates semester CHECK -> both must disappear
        assertThrows(SQLException.class, () -> Tx.inTransaction(db, con -> {
            long id = dao.insertUser(con, "rollback@college.edu", "Roll Back", Role.STUDENT, "h");
            dao.insertStudent(con, id, "CS2024999", "CS", 99);
            return id;
        }));
        try (Connection con = db.open()) {
            assertFalse(dao.emailExists(con, "rollback@college.edu"), "user row was rolled back");
        }
    }

    @Test
    void transactionCommitsWhenAllStatementsSucceed() throws SQLException {
        long id = Tx.inTransaction(db, con -> {
            long newId = dao.insertUser(con, "prof@college.edu", "Dr. Meera Iyer", Role.PROFESSOR, "h");
            dao.insertProfessor(con, newId, "EMP001", "Computer Science", "Associate Professor");
            return newId;
        });
        try (Connection con = db.open()) {
            assertEquals(Role.PROFESSOR, dao.findById(con, id).orElseThrow().role());
        }
    }
}
