package com.classsync.service;

import com.classsync.Fixtures;
import com.classsync.TestDb;
import com.classsync.config.Database;
import com.classsync.model.CoursePermission;
import com.classsync.model.Lecture;
import com.classsync.model.LectureStatus;
import com.classsync.model.User;
import com.classsync.service.AppException.AccessDeniedException;
import com.classsync.service.AppException.NotFoundException;
import com.classsync.service.AppException.ValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class LectureServiceIT {

    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    private Fixtures fx;
    private LectureService service;
    private User prof;
    private User student;
    private User outsider;
    private User cr;
    private long courseId;

    @BeforeEach
    void setUp() throws SQLException {
        Database db = TestDb.reset();
        fx = new Fixtures(db);
        service = new LectureService(db, new AccessPolicy(), Clock.system(ZONE));
        prof = fx.professor("Prof");
        student = fx.student("Student");
        outsider = fx.student("Outsider");
        cr = fx.cr("CR");
        courseId = fx.course(prof, "CS301");
        fx.enroll(courseId, student, cr);
    }

    private LectureService.LectureForm form(String topic, LocalDate date) {
        return new LectureService.LectureForm(courseId, null, 1, null, topic, "d", "o", "Read chapter 1", "R1",
                date, LocalTime.of(10, 0), LocalTime.of(11, 0));
    }

    @Test
    void draftsAreHiddenFromStudentsUntilPublished() throws SQLException {
        long id = service.create(prof, form("Normalization", LocalDate.now(ZONE).plusDays(2)));
        assertThrows(NotFoundException.class, () -> service.get(student, id));
        assertTrue(service.upcoming(student, 10).isEmpty());

        service.changeStatus(prof, id, LectureStatus.PUBLISHED);
        Lecture seen = service.get(student, id);
        assertEquals("Read chapter 1", seen.preparation(), "student sees preparation instructions");
        assertEquals(1, service.upcoming(student, 10).size());
    }

    @Test
    void lectureNumberIsAssignedAndTimesUseAcademicZone() throws SQLException {
        LocalDate day = LocalDate.now(ZONE).plusDays(1);
        long first = service.create(prof, form("Alpha", day));
        long second = service.create(prof, form("Beta", day));
        assertEquals(1, service.get(prof, first).lectureNo());
        assertEquals(2, service.get(prof, second).lectureNo());
        assertEquals(LocalTime.of(10, 0), service.get(prof, first).startsAt().atZone(ZONE).toLocalTime());
    }

    @Test
    void validationRejectsBadTimes() {
        LocalDate day = LocalDate.now(ZONE).plusDays(1);
        assertThrows(ValidationException.class, () -> service.create(prof, new LectureService.LectureForm(courseId, null, 1,
                null, "Topic", "", "", "", "", day, LocalTime.of(11, 0), LocalTime.of(10, 0))));
        assertThrows(ValidationException.class, () -> service.create(prof, new LectureService.LectureForm(courseId, null, 11,
                null, "Topic", "", "", "", "", day, LocalTime.of(9, 0), LocalTime.of(10, 0))), "unit out of range");
    }

    @Test
    void outsidersAndStudentsCannotCreateOrReadOtherCourses() throws SQLException {
        long id = fx.lecture(courseId, prof, 1, 5, LectureStatus.PUBLISHED);
        assertThrows(NotFoundException.class, () -> service.get(outsider, id));
        assertThrows(AccessDeniedException.class, () -> service.create(student, form("X", LocalDate.now(ZONE))));
        assertThrows(AccessDeniedException.class, () -> service.forCourse(outsider, courseId));
    }

    @Test
    void crNeedsDelegationToUpdateLecturesAndRevocationApplies() throws SQLException {
        fx.staff(courseId, cr, prof, Set.of());
        long id = fx.lecture(courseId, prof, 1, 24, LectureStatus.DRAFT);
        assertEquals(1, service.forCourse(cr, courseId).size(), "staff CR sees drafts by default");
        assertThrows(AccessDeniedException.class, () -> service.changeStatus(cr, id, LectureStatus.PUBLISHED));

        fx.staff(courseId, cr, prof, Set.of(CoursePermission.UPDATE_LECTURES));
        service.changeStatus(cr, id, LectureStatus.PUBLISHED);
        assertThrows(AccessDeniedException.class, () -> service.changeStatus(cr, id, LectureStatus.CANCELLED),
                "cancelling is professor-only");

        fx.staff(courseId, cr, prof, Set.of());
        assertThrows(AccessDeniedException.class, () -> service.changeStatus(cr, id, LectureStatus.DRAFT));
    }

    @Test
    void invalidTransitionsAreRejected() throws SQLException {
        long id = fx.lecture(courseId, prof, 1, -2, LectureStatus.COMPLETED);
        assertThrows(ValidationException.class, () -> service.changeStatus(prof, id, LectureStatus.PUBLISHED));
        assertThrows(ValidationException.class, () -> service.update(prof, id, form("New", LocalDate.now(ZONE))));
    }

    @Test
    void onlyDraftsWithoutHistoryCanBeDeleted() throws SQLException {
        long published = fx.lecture(courseId, prof, 1, 5, LectureStatus.PUBLISHED);
        assertThrows(ValidationException.class, () -> service.deleteDraft(prof, published));
        long draft = fx.lecture(courseId, prof, 2, 5, LectureStatus.DRAFT);
        service.deleteDraft(prof, draft);
        assertThrows(NotFoundException.class, () -> service.get(prof, draft));
    }

    @Test
    void searchTreatsWildcardsLiterally() throws SQLException {
        fx.lecture(courseId, prof, 1, 5, LectureStatus.PUBLISHED);
        assertTrue(service.search(student, "%%", 10).isEmpty(), "'%' is not a wildcard");
        assertEquals(1, service.search(student, "topic", 10).size());
    }
}
