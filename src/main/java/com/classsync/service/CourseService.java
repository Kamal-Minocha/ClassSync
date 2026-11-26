package com.classsync.service;

import com.classsync.config.Database;
import com.classsync.config.Tx;
import com.classsync.dao.AuditDao;
import com.classsync.dao.CourseDao;
import com.classsync.model.Course;
import com.classsync.model.CoursePermission;
import com.classsync.model.EnrolledStudent;
import com.classsync.model.Role;
import com.classsync.model.StaffMember;
import com.classsync.model.Topic;
import com.classsync.model.User;
import com.classsync.service.AppException.AccessDeniedException;
import com.classsync.service.AppException.NotFoundException;
import com.classsync.service.AppException.ValidationException;
import com.classsync.util.Validation;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Courses, enrollment and CR delegation. Only a course's own professor can change them. */
public class CourseService {

    public record CourseForm(String code, String name, String department, int semester, String description) {
    }

    private final Database db;
    private final CourseDao courses = new CourseDao();
    private final AuditDao audit = new AuditDao();
    private final AccessPolicy access;

    public CourseService(Database db, AccessPolicy access) {
        this.db = db;
        this.access = access;
    }

    public List<Course> myCourses(User u, boolean includeArchived) throws SQLException {
        try (Connection con = db.open()) {
            return u.role() == Role.PROFESSOR
                    ? courses.listForProfessor(con, u.id(), includeArchived)
                    : courses.listForStudent(con, u.id(), includeArchived);
        }
    }

    /** Courses where a CR has staff rights (empty for others). */
    public List<Course> staffedCourses(User u) throws SQLException {
        if (u.role() != Role.CR) {
            return List.of();
        }
        try (Connection con = db.open()) {
            return courses.listStaffedCourses(con, u.id());
        }
    }

    public Course get(User u, long courseId) throws SQLException {
        try (Connection con = db.open()) {
            access.requireMember(con, u, courseId);
            return courses.findById(con, courseId).orElseThrow(() -> new NotFoundException("Course not found."));
        }
    }

    /** A professor creates a course assigned to themselves. */
    public long create(User u, CourseForm f) throws SQLException {
        if (u.role() != Role.PROFESSOR) {
            throw new AccessDeniedException("Only professors can create courses.");
        }
        String code = Validation.text(f.code(), "Course code", 3, 12).toUpperCase(Locale.ROOT);
        if (!code.matches("[A-Z0-9-]{3,12}")) {
            throw new ValidationException("Course code may contain only letters, digits and '-'.");
        }
        String name = Validation.text(f.name(), "Course name", 3, 120);
        String dept = Validation.text(f.department(), "Department", 2, 80);
        int sem = Validation.range(f.semester(), "Semester", 1, 12);
        String desc = Validation.optional(f.description(), "Description", 2000);
        try {
            return Tx.inTransaction(db, con -> {
                long id = courses.insert(con, code, name, dept, sem, desc, u.id());
                audit.log(con, u.id(), "COURSE_CREATE", "course", id, code);
                return id;
            });
        } catch (SQLException e) {
            if ("23505".equals(e.getSQLState())) {
                throw new ValidationException("A course with code " + code + " already exists.");
            }
            throw e;
        }
    }

    public void update(User u, long courseId, CourseForm f) throws SQLException {
        String name = Validation.text(f.name(), "Course name", 3, 120);
        String dept = Validation.text(f.department(), "Department", 2, 80);
        int sem = Validation.range(f.semester(), "Semester", 1, 12);
        String desc = Validation.optional(f.description(), "Description", 2000);
        Tx.inTransaction(db, con -> {
            access.requireCourseProfessor(con, u, courseId);
            courses.update(con, courseId, name, dept, sem, desc);
            audit.log(con, u.id(), "COURSE_UPDATE", "course", courseId, "");
            return null;
        });
    }

    /** Courses are archived, never deleted, so attendance and scores stay intact. */
    public void setArchived(User u, long courseId, boolean archived) throws SQLException {
        Tx.inTransaction(db, con -> {
            access.requireCourseProfessor(con, u, courseId);
            courses.setArchived(con, courseId, archived);
            audit.log(con, u.id(), archived ? "COURSE_ARCHIVE" : "COURSE_RESTORE", "course", courseId, "");
            return null;
        });
    }

    // ---------------------------------------------------------------- enrollment

    public EnrolledStudent enroll(User u, long courseId, String enrollmentNo) throws SQLException {
        String no = Validation.text(enrollmentNo, "Enrollment number", 1, 15).toUpperCase(Locale.ROOT);
        try {
            return Tx.inTransaction(db, con -> {
                access.requireCourseProfessor(con, u, courseId);
                EnrolledStudent s = courses.findStudentByEnrollmentNo(con, no)
                        .orElseThrow(() -> new ValidationException("No active student with enrollment number " + no + "."));
                if (courses.isEnrolled(con, s.userId(), courseId)) {
                    throw new ValidationException(s.fullName() + " is already enrolled.");
                }
                courses.enroll(con, courseId, s.userId());
                audit.log(con, u.id(), "ENROLL", "course", courseId, "student " + s.userId());
                return s;
            });
        } catch (SQLException e) {
            if ("23505".equals(e.getSQLState())) {
                throw new ValidationException("This student is already enrolled.");
            }
            throw e;
        }
    }

    public void unenroll(User u, long courseId, long studentId) throws SQLException {
        Tx.inTransaction(db, con -> {
            access.requireCourseProfessor(con, u, courseId);
            courses.unenroll(con, courseId, studentId);   // course_staff row cascades away
            audit.log(con, u.id(), "UNENROLL", "course", courseId, "student " + studentId);
            return null;
        });
    }

    public List<EnrolledStudent> students(User u, long courseId) throws SQLException {
        try (Connection con = db.open()) {
            access.requireCourseProfessor(con, u, courseId);
            return courses.listEnrolled(con, courseId);
        }
    }

    // ---------------------------------------------------------------- CR delegation

    public List<StaffMember> staff(User u, long courseId) throws SQLException {
        try (Connection con = db.open()) {
            access.requireCourseProfessor(con, u, courseId);
            return courses.listStaff(con, courseId);
        }
    }

    /**
     * Grants (or replaces) a CR's delegated permissions. Only the course professor can call this,
     * so a CR can never grant permissions to themselves.
     */
    public void setStaffPermissions(User u, long courseId, long crUserId, Set<CoursePermission> perms)
            throws SQLException {
        Set<CoursePermission> delegable = EnumSet.noneOf(CoursePermission.class);
        perms.stream().filter(CoursePermission::delegable).forEach(delegable::add);
        Tx.inTransaction(db, con -> {
            access.requireCourseProfessor(con, u, courseId);
            EnrolledStudent cr = courses.listEnrolled(con, courseId).stream()
                    .filter(s -> s.userId() == crUserId).findFirst()
                    .orElseThrow(() -> new ValidationException("The student must be enrolled in this course."));
            if (cr.role() != Role.CR) {
                throw new ValidationException(cr.fullName() + " does not have a Class Representative account.");
            }
            courses.upsertStaff(con, courseId, crUserId, delegable, u.id());
            audit.log(con, u.id(), "CR_PERMISSIONS", "course", courseId, "cr " + crUserId + " " + delegable);
            return null;
        });
    }

    public void removeStaff(User u, long courseId, long crUserId) throws SQLException {
        Tx.inTransaction(db, con -> {
            access.requireCourseProfessor(con, u, courseId);
            courses.removeStaff(con, courseId, crUserId);
            audit.log(con, u.id(), "CR_REVOKE", "course", courseId, "cr " + crUserId);
            return null;
        });
    }

    /** The current user's own staff rights in a course (empty set when none). */
    public Set<CoursePermission> myPermissions(User u, long courseId) throws SQLException {
        try (Connection con = db.open()) {
            if (access.isCourseProfessor(con, u, courseId)) {
                return EnumSet.allOf(CoursePermission.class);
            }
            if (u.role() != Role.CR) {
                return EnumSet.noneOf(CoursePermission.class);
            }
            return courses.staffPermissions(con, u.id(), courseId).orElse(EnumSet.noneOf(CoursePermission.class));
        }
    }

    // ---------------------------------------------------------------- topics

    public List<Topic> topics(User u, long courseId) throws SQLException {
        try (Connection con = db.open()) {
            access.requireMember(con, u, courseId);
            return courses.listTopics(con, courseId);
        }
    }

    public long addTopic(User u, long courseId, int unitNo, String name) throws SQLException {
        String n = Validation.text(name, "Topic name", 2, 150);
        int unit = Validation.range(unitNo, "Unit", 1, 10);
        try {
            return Tx.inTransaction(db, con -> {
                access.requireCourseProfessor(con, u, courseId);
                int seq = courses.listTopics(con, courseId).size() + 1;
                return courses.insertTopic(con, courseId, unit, n, seq);
            });
        } catch (SQLException e) {
            if ("23505".equals(e.getSQLState())) {
                throw new ValidationException("This topic already exists in the course.");
            }
            throw e;
        }
    }
}
