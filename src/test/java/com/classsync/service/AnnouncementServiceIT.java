package com.classsync.service;

import com.classsync.Fixtures;
import com.classsync.TestDb;
import com.classsync.config.Database;
import com.classsync.dao.NotificationDao;
import com.classsync.model.AnnouncementCategory;
import com.classsync.model.CoursePermission;
import com.classsync.model.LectureStatus;
import com.classsync.model.MaterialKind;
import com.classsync.model.QuestionKind;
import com.classsync.model.ReviewPolicy;
import com.classsync.model.User;
import com.classsync.service.AnnouncementService.AnnouncementForm;
import com.classsync.service.AppException.AccessDeniedException;
import com.classsync.util.FileStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Announcements, audiences, automatic notifications and duplicate protection. */
class AnnouncementServiceIT {

    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    @TempDir Path tmp;
    private Database db;
    private Fixtures fx;
    private AnnouncementService announcements;
    private NotificationService notifications;
    private LectureService lectures;
    private CourseService courses;
    private AccessPolicy access;
    private User prof;
    private User asha;
    private User neha;
    private User cr;
    private User outsider;
    private long courseId;

    @BeforeEach
    void setUp() throws SQLException {
        db = TestDb.reset();
        fx = new Fixtures(db);
        access = new AccessPolicy();
        lectures = new LectureService(db, access, Clock.system(ZONE));
        announcements = new AnnouncementService(db, access, lectures);
        notifications = new NotificationService(db);
        courses = new CourseService(db, access);
        prof = fx.professor("Prof");
        asha = fx.student("Asha");
        neha = fx.student("Neha");
        cr = fx.cr("Ravi");
        outsider = fx.student("Outsider");
        courseId = fx.course(prof, "CS301");
        fx.enroll(courseId, asha, neha, cr);
        fx.staff(courseId, cr, prof, EnumSet.noneOf(CoursePermission.class));
        long other = fx.course(fx.professor("Other"), "CS999");
        fx.enroll(other, outsider);
    }

    private AnnouncementForm form(String title) {
        return new AnnouncementForm(courseId, null, AnnouncementCategory.GENERAL, title, "Details");
    }

    @Test
    void professorAnnouncementReachesEveryEnrolledStudentOnce() throws SQLException {
        long id = announcements.publish(prof, form("Mid-term on Friday"));
        for (User u : List.of(asha, neha, cr)) {
            var list = notifications.list(u, true, 10);
            assertEquals(1, list.size());
            assertEquals("ANNOUNCEMENT", list.get(0).linkType());
            assertEquals(id, list.get(0).linkId());
        }
        assertEquals(0, notifications.unreadCount(outsider), "other courses are not notified");
        assertEquals(0, notifications.unreadCount(prof), "the author is not notified");
        assertEquals(1, announcements.list(asha, null, 10).size());
        assertTrue(announcements.list(outsider, null, 10).isEmpty());
    }

    @Test
    void crNeedsDelegationAndTheProfessorIsToldWhenACrPosts() throws SQLException {
        assertThrows(AccessDeniedException.class, () -> announcements.publish(cr, form("Class moved")));
        assertThrows(AccessDeniedException.class, () -> announcements.publish(asha, form("Party!")));
        courses.setStaffPermissions(prof, courseId, cr.id(), EnumSet.of(CoursePermission.PUBLISH_ANNOUNCEMENTS));
        announcements.publish(cr, new AnnouncementForm(courseId, null, AnnouncementCategory.URGENT, "Lab moved to LH-2", ""));
        assertEquals(1, notifications.unreadCount(prof));
        assertEquals(0, notifications.unreadCount(cr));
        assertTrue(notifications.list(asha, true, 5).get(0).title().startsWith("URGENT"));
        courses.setStaffPermissions(prof, courseId, cr.id(), EnumSet.noneOf(CoursePermission.class));
        assertThrows(AccessDeniedException.class, () -> announcements.publish(cr, form("Again")), "revoked");
    }

    @Test
    void retriedNotificationsAreNeverDuplicated() throws SQLException {
        NotificationDao dao = new NotificationDao();
        var m = new NotificationDao.Message("LECTURE", "Room change", "", "LECTURE", 1L, "lecture-room:1:LH-2");
        try (Connection con = db.open()) {
            assertEquals(3, dao.notifyCourseLearners(con, courseId, prof.id(), m));
            assertEquals(0, dao.notifyCourseLearners(con, courseId, prof.id(), m), "same event again: nothing new");
            assertEquals(0, dao.notifyUsers(con, List.of(asha.id()), m));
        }
        assertEquals(1, notifications.unreadCount(asha));
    }

    @Test
    void readStateIsPerUser() throws SQLException {
        announcements.publish(prof, form("One"));
        announcements.publish(prof, form("Two"));
        long ashaFirst = notifications.list(asha, false, 10).get(0).id();
        notifications.markRead(neha, ashaFirst);   // not Neha's notification: no effect
        assertEquals(2, notifications.unreadCount(asha));
        notifications.markRead(asha, ashaFirst);
        assertEquals(1, notifications.unreadCount(asha));
        assertEquals(2, notifications.unreadCount(neha));
        assertEquals(2, notifications.markAllRead(neha));
        assertEquals(0, notifications.unreadCount(neha));
    }

    @Test
    void lectureCancellationAndRoomChangesNotifyStudents() throws SQLException {
        LocalDate day = LocalDate.now(ZONE).plusDays(3);
        long id = lectures.create(prof, new LectureService.LectureForm(courseId, null, 1, null, "Indexing", "", "", "",
                "LH-4", day, LocalTime.of(10, 0), LocalTime.of(11, 0)));
        lectures.changeStatus(prof, id, LectureStatus.PUBLISHED);
        lectures.update(prof, id, new LectureService.LectureForm(courseId, 1, 1, null, "Indexing", "", "", "",
                "LH-2", day, LocalTime.of(10, 0), LocalTime.of(11, 0)));
        lectures.changeStatus(prof, id, LectureStatus.CANCELLED);
        var titles = notifications.list(asha, true, 10).stream().map(n -> n.title()).toList();
        assertEquals(2, titles.size());
        assertTrue(titles.stream().anyMatch(t -> t.startsWith("Room change") && t.contains("LH-2")));
        assertTrue(titles.stream().anyMatch(t -> t.startsWith("Cancelled")));
    }

    @Test
    void publishingQuizzesAndMaterialsNotifiesOncePerItem() throws Exception {
        QuizService quizzes = new QuizService(db, access, Clock.system(ZONE));
        ZonedDateTime o = Instant.now().minus(1, ChronoUnit.HOURS).atZone(ZONE);
        ZonedDateTime c = Instant.now().plus(1, ChronoUnit.HOURS).atZone(ZONE);
        long quiz = quizzes.create(prof, new QuizService.QuizForm(courseId, null, "Quick check", "", o.toLocalDate(),
                o.toLocalTime(), c.toLocalDate(), c.toLocalTime(), 10, ReviewPolicy.AFTER_CLOSE, false));
        quizzes.addQuestion(prof, quiz, new QuizService.QuestionForm(QuestionKind.TRUE_FALSE, "SQL is declarative", 1, null, "", null, 0));
        quizzes.publish(prof, quiz);
        quizzes.unpublish(prof, quiz);
        quizzes.publish(prof, quiz);   // republishing does not notify again

        MaterialService materials = new MaterialService(db, access, new FileStorage(tmp.resolve("u"), 1 << 20), lectures);
        Path file = tmp.resolve("slides.pdf");
        Files.writeString(file, "%PDF-1.4\n");
        long material = materials.upload(prof, new MaterialService.UploadForm(courseId, null, MaterialKind.MATERIAL,
                "Week 1 slides", "", null, file, false));
        assertEquals(1, notifications.unreadCount(asha), "unpublished upload: no notification yet");
        materials.setPublished(prof, material, true);
        materials.setPublished(prof, material, false);
        materials.setPublished(prof, material, true);
        assertEquals(2, notifications.unreadCount(asha), "one for the quiz, one for the material");
    }

    @Test
    void finalizingAttendanceWarnsStudentsBelowThreshold() throws SQLException {
        AttendanceService attendance = new AttendanceService(db, access, 120);
        long lecture = fx.lecture(courseId, prof, 1, -1, LectureStatus.PUBLISHED);
        var live = attendance.start(prof, lecture);
        attendance.submitCode(asha, live.sessionId(), live.code());
        attendance.finalizeAttendance(prof, lecture);
        assertEquals(0, notifications.unreadCount(asha), "100% present");
        var warning = notifications.list(neha, true, 5);
        assertEquals(1, warning.size());
        assertEquals("ATTENDANCE", warning.get(0).kind());
        assertTrue(warning.get(0).title().contains("0%"));
    }
}
