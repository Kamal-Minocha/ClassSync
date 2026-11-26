package com.classsync.service;

import com.classsync.Fixtures;
import com.classsync.TestDb;
import com.classsync.config.Database;
import com.classsync.model.CoursePermission;
import com.classsync.model.User;
import com.classsync.service.AppException.AccessDeniedException;
import com.classsync.service.AppException.ValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CourseServiceIT {

    private Database db;
    private Fixtures fx;
    private CourseService service;
    private AccessPolicy access;
    private User prof;
    private User otherProf;
    private User student;
    private User cr;
    private long courseId;

    @BeforeEach
    void setUp() throws SQLException {
        db = TestDb.reset();
        fx = new Fixtures(db);
        access = new AccessPolicy();
        service = new CourseService(db, access);
        prof = fx.professor("Dr. Meera Iyer");
        otherProf = fx.professor("Dr. Other");
        student = fx.student("Asha Rao");
        cr = fx.cr("Ravi Kumar");
        courseId = service.create(prof, new CourseService.CourseForm("cs301", "Database Systems", "CS", 5, ""));
    }

    @Test
    void professorCreatesCourseAssignedToSelfWithUniqueCode() throws SQLException {
        assertEquals("CS301", service.get(prof, courseId).code());
        assertEquals(prof.id(), service.get(prof, courseId).professorId());
        assertThrows(ValidationException.class,
                () -> service.create(otherProf, new CourseService.CourseForm("CS301", "Dup", "CS", 5, "")));
    }

    @Test
    void studentsCannotCreateCourses() {
        assertThrows(AccessDeniedException.class,
                () -> service.create(student, new CourseService.CourseForm("CS999", "X course", "CS", 1, "")));
    }

    @Test
    void onlyAssignedProfessorManagesEnrollmentAndDuplicatesAreRejected() throws SQLException {
        String enrollmentNo = new com.classsync.dao.UserDao().findStudentProfile(db.open(), student.id()).orElseThrow().enrollmentNo();
        assertThrows(AccessDeniedException.class, () -> service.enroll(otherProf, courseId, enrollmentNo));
        service.enroll(prof, courseId, enrollmentNo.toLowerCase());
        assertThrows(ValidationException.class, () -> service.enroll(prof, courseId, enrollmentNo));
        assertEquals(1, service.students(prof, courseId).size());
        assertThrows(AccessDeniedException.class, () -> service.students(student, courseId));
    }

    @Test
    void nonMemberCannotReadCourse() {
        assertThrows(AccessDeniedException.class, () -> service.get(student, courseId));
        assertThrows(AccessDeniedException.class, () -> service.get(otherProf, courseId));
    }

    @Test
    void crDelegationRequiresEnrollmentAndCrRole() throws SQLException {
        assertThrows(ValidationException.class,
                () -> service.setStaffPermissions(prof, courseId, cr.id(), Set.of()), "not enrolled");
        fx.enroll(courseId, cr, student);
        assertThrows(ValidationException.class,
                () -> service.setStaffPermissions(prof, courseId, student.id(), Set.of()), "plain student is not a CR");
        service.setStaffPermissions(prof, courseId, cr.id(), Set.of(CoursePermission.REVIEW_NOTES));
        assertEquals(Set.of(CoursePermission.VIEW_SCHEDULE, CoursePermission.UPLOAD_MATERIALS, CoursePermission.REVIEW_NOTES),
                service.myPermissions(cr, courseId));
    }

    @Test
    void crCannotGrantPermissionsToThemselves() throws SQLException {
        fx.enroll(courseId, cr);
        fx.staff(courseId, cr, prof, Set.of());
        assertThrows(AccessDeniedException.class,
                () -> service.setStaffPermissions(cr, courseId, cr.id(), EnumSet.allOf(CoursePermission.class)));
    }

    @Test
    void revocationTakesEffectOnNextCall() throws SQLException {
        fx.enroll(courseId, cr);
        service.setStaffPermissions(prof, courseId, cr.id(), Set.of(CoursePermission.UPDATE_LECTURES));
        try (var con = db.open()) {
            assertTrue(access.has(con, cr, courseId, CoursePermission.UPDATE_LECTURES));
        }
        service.setStaffPermissions(prof, courseId, cr.id(), Set.of());
        try (var con = db.open()) {
            assertFalse(access.has(con, cr, courseId, CoursePermission.UPDATE_LECTURES), "narrowed grant applies immediately");
        }
        service.removeStaff(prof, courseId, cr.id());
        try (var con = db.open()) {
            assertFalse(access.has(con, cr, courseId, CoursePermission.UPLOAD_MATERIALS), "removed staff loses defaults");
        }
    }

    @Test
    void unenrollingRemovesDelegationThroughForeignKeyCascade() throws SQLException {
        fx.enroll(courseId, cr);
        service.setStaffPermissions(prof, courseId, cr.id(), Set.of(CoursePermission.REVIEW_NOTES));
        service.unenroll(prof, courseId, cr.id());
        assertTrue(service.staff(prof, courseId).isEmpty());
    }

    @Test
    void archivingHidesCourseFromDefaultListButKeepsIt() throws SQLException {
        service.setArchived(prof, courseId, true);
        assertTrue(service.myCourses(prof, false).isEmpty());
        assertEquals(1, service.myCourses(prof, true).size());
        assertThrows(AccessDeniedException.class, () -> service.setArchived(otherProf, courseId, false));
    }
}
