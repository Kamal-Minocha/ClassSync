package com.classsync;

import com.classsync.config.Database;
import com.classsync.dao.CourseDao;
import com.classsync.dao.LectureDao;
import com.classsync.dao.UserDao;
import com.classsync.model.CoursePermission;
import com.classsync.model.LectureStatus;
import com.classsync.model.Role;
import com.classsync.model.User;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;

/** Builds small, explicit test worlds directly through the DAOs. */
public class Fixtures {

    private final Database db;
    private final UserDao users = new UserDao();
    private final CourseDao courses = new CourseDao();
    private final LectureDao lectures = new LectureDao();
    private int counter;

    public Fixtures(Database db) {
        this.db = db;
    }

    public User professor(String name) throws SQLException {
        try (Connection con = db.open()) {
            int n = ++counter;
            long id = users.insertUser(con, "prof" + n + "@college.edu", name, Role.PROFESSOR, "x");
            users.insertProfessor(con, id, "EMP" + n, "CS", "Professor");
            return users.findById(con, id).orElseThrow();
        }
    }

    public User student(String name) throws SQLException {
        return learner(name, Role.STUDENT);
    }

    public User cr(String name) throws SQLException {
        return learner(name, Role.CR);
    }

    private User learner(String name, Role role) throws SQLException {
        try (Connection con = db.open()) {
            int n = ++counter;
            long id = users.insertUser(con, "stu" + n + "@college.edu", name, role, "x");
            users.insertStudent(con, id, "CS2024%03d".formatted(n), "CS", 3);
            return users.findById(con, id).orElseThrow();
        }
    }

    public long course(User professor, String code) throws SQLException {
        try (Connection con = db.open()) {
            return courses.insert(con, code, code + " course", "CS", 3, "", professor.id());
        }
    }

    public void enroll(long courseId, User... students) throws SQLException {
        try (Connection con = db.open()) {
            for (User s : students) {
                courses.enroll(con, courseId, s.id());
            }
        }
    }

    public void staff(long courseId, User cr, User professor, Set<CoursePermission> perms) throws SQLException {
        try (Connection con = db.open()) {
            courses.upsertStaff(con, courseId, cr.id(), perms, professor.id());
        }
    }

    /** Lecture starting hoursFromNow hours from now (negative = past), 1 hour long. */
    public long lecture(long courseId, User creator, int number, int hoursFromNow, LectureStatus status) throws SQLException {
        try (Connection con = db.open()) {
            Instant start = Instant.now().plus(hoursFromNow, ChronoUnit.HOURS);
            long id = lectures.insert(con, new LectureDao.LectureData(courseId, number, 1, null, "Topic " + number,
                    "desc", "obj", "prep", "R101", start, start.plus(1, ChronoUnit.HOURS)), creator.id());
            if (status != LectureStatus.DRAFT) {
                try (var ps = con.prepareStatement("UPDATE lectures SET status = ? WHERE id = ?")) {
                    ps.setString(1, status.name());
                    ps.setLong(2, id);
                    ps.executeUpdate();
                }
            }
            return id;
        }
    }
}
