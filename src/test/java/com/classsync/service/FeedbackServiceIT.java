package com.classsync.service;

import com.classsync.Fixtures;
import com.classsync.TestDb;
import com.classsync.config.Database;
import com.classsync.model.CoursePermission;
import com.classsync.model.LectureStatus;
import com.classsync.model.User;
import com.classsync.service.AppException.AccessDeniedException;
import com.classsync.service.AppException.ValidationException;
import com.classsync.service.FeedbackService.FeedbackForm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.ZoneId;
import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.*;

/** Lecture feedback: one per student, eligibility, edit policy, anonymity and aggregates. */
class FeedbackServiceIT {

    private Database db;
    private Fixtures fx;
    private FeedbackService feedback;
    private LectureService lectures;
    private User prof;
    private User asha;
    private User neha;
    private User cr;
    private User outsider;
    private long courseId;
    private long lecture;

    @BeforeEach
    void setUp() throws SQLException {
        db = TestDb.reset();
        fx = new Fixtures(db);
        AccessPolicy access = new AccessPolicy();
        feedback = new FeedbackService(db, access);
        lectures = new LectureService(db, access, Clock.system(ZoneId.of("Asia/Kolkata")));
        prof = fx.professor("Prof");
        asha = fx.student("Asha");
        neha = fx.student("Neha");
        cr = fx.cr("Ravi");
        outsider = fx.student("Outsider");
        courseId = fx.course(prof, "CS301");
        fx.enroll(courseId, asha, neha, cr);
        fx.staff(courseId, cr, prof, EnumSet.allOf(CoursePermission.class));
        lecture = fx.lecture(courseId, prof, 1, -2, LectureStatus.COMPLETED);
        lectures.setFeedbackSettings(prof, lecture, true, false);
    }

    private static FeedbackForm form(int u, int c, int p, int us, String difficult, String suggestion) {
        return new FeedbackForm(u, c, p, us, difficult, suggestion);
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
    void oneFeedbackPerStudentAndEditingUpdatesTheSameRow() throws SQLException {
        feedback.submit(asha, lecture, form(3, 3, 3, 3, "", ""));
        feedback.submit(asha, lecture, form(5, 4, 4, 5, "Joins", "More examples"));
        assertEquals(1, count("SELECT count(*) FROM feedback WHERE student_id = ?", asha.id()));
        assertEquals(5, feedback.mine(asha, lecture).own().ratings().understanding());
        SQLException dup = assertThrows(SQLException.class, () -> {
            try (Connection con = db.open(); PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO feedback (lecture_id, student_id, anonymous, understanding, clarity, pace, usefulness)
                    VALUES (?, ?, false, 1, 1, 1, 1)""")) {
                ps.setLong(1, lecture);
                ps.setLong(2, asha.id());
                ps.executeUpdate();
            }
        });
        assertEquals("23505", dup.getSQLState(), "UNIQUE (lecture_id, student_id)");
    }

    @Test
    void ratingsMustBeBetweenOneAndFive() {
        assertThrows(ValidationException.class, () -> feedback.submit(asha, lecture, form(0, 3, 3, 3, "", "")));
        assertThrows(ValidationException.class, () -> feedback.submit(asha, lecture, form(3, 6, 3, 3, "", "")));
    }

    @Test
    void eligibilityRules() throws SQLException {
        assertThrows(AccessDeniedException.class, () -> feedback.submit(outsider, lecture, form(4, 4, 4, 4, "", "")));
        assertThrows(ValidationException.class, () -> feedback.submit(prof, lecture, form(4, 4, 4, 4, "", "")), "professors do not rate");

        long future = fx.lecture(courseId, prof, 2, 24, LectureStatus.PUBLISHED);
        lectures.setFeedbackSettings(prof, future, true, false);
        assertThrows(ValidationException.class, () -> feedback.submit(asha, future, form(4, 4, 4, 4, "", "")), "not started");

        long closed = fx.lecture(courseId, prof, 3, -5, LectureStatus.COMPLETED);
        assertFalse(feedback.mine(asha, closed).eligible(), "feedback is closed by default");
        assertThrows(ValidationException.class, () -> feedback.submit(asha, closed, form(4, 4, 4, 4, "", "")));

        long cancelled = fx.lecture(courseId, prof, 4, -5, LectureStatus.CANCELLED);
        lectures.setFeedbackSettings(prof, cancelled, true, false);
        assertThrows(ValidationException.class, () -> feedback.submit(asha, cancelled, form(4, 4, 4, 4, "", "")));

        feedback.submit(cr, lecture, form(4, 4, 4, 4, "", ""));   // a CR gives feedback like any student
    }

    @Test
    void closingFeedbackFreezesSubmissions() throws SQLException {
        feedback.submit(asha, lecture, form(4, 4, 4, 4, "", ""));
        lectures.setFeedbackSettings(prof, lecture, false, false);
        assertThrows(ValidationException.class, () -> feedback.submit(asha, lecture, form(1, 1, 1, 1, "", "")));
        assertEquals(4, feedback.mine(asha, lecture).own().ratings().clarity());
    }

    @Test
    void anonymousFeedbackNeverRevealsTheAuthor() throws SQLException {
        lectures.setFeedbackSettings(prof, lecture, true, true);
        feedback.submit(asha, lecture, form(2, 2, 2, 2, "Normal forms", "Slower please"));
        // the professor switches anonymity off afterwards: Asha's earlier feedback must stay anonymous
        lectures.setFeedbackSettings(prof, lecture, true, false);
        feedback.submit(neha, lecture, form(4, 4, 4, 4, "", "Great examples"));
        feedback.submit(asha, lecture, form(3, 2, 2, 2, "Normal forms", "Slower please"));   // edit keeps anonymity

        var report = feedback.report(prof, lecture);
        assertEquals(2, report.comments().size());
        var ashaComment = report.comments().stream().filter(c -> c.suggestions().equals("Slower please")).findFirst().orElseThrow();
        assertNull(ashaComment.author(), "anonymous author is not even selected");
        var nehaComment = report.comments().stream().filter(c -> c.suggestions().equals("Great examples")).findFirst().orElseThrow();
        assertEquals("Neha", nehaComment.author());
        for (var rc : com.classsync.dao.FeedbackDao.Comment.class.getRecordComponents()) {
            assertNotEquals("studentId", rc.getName(), "report rows carry no student id");
        }
        assertEquals(0, count("SELECT count(*) FROM audit_logs WHERE actor_id = ?", asha.id()),
                "feedback writes no audit entry that could identify the author");
    }

    @Test
    void reportAggregatesAndParticipation() throws SQLException {
        var empty = feedback.report(prof, lecture);
        assertEquals(0, empty.summary().count());
        assertNull(empty.summary().understanding(), "no submissions → no average");
        assertNull(empty.summary().overall());

        feedback.submit(asha, lecture, form(5, 4, 3, 2, "", ""));
        feedback.submit(neha, lecture, form(3, 4, 5, 4, "", ""));
        var r = feedback.report(prof, lecture);
        assertEquals(2, r.summary().count());
        assertEquals(4.0, r.summary().understanding(), 1e-9);
        assertEquals(4.0, r.summary().clarity(), 1e-9);
        assertEquals(4.0, r.summary().pace(), 1e-9);
        assertEquals(3.0, r.summary().usefulness(), 1e-9);
        assertEquals(3.75, r.summary().overall(), 1e-9);
        assertEquals(2.0 / 3, r.participation().orElseThrow(), 1e-9);
        assertEquals(1, feedback.trend(prof, courseId).size());
        assertEquals(1, feedback.pending(cr).size(), "the CR has not answered yet");
        assertTrue(feedback.pending(asha).isEmpty());
    }

    @Test
    void onlyTheCourseProfessorSeesReports() {
        assertThrows(AccessDeniedException.class, () -> feedback.report(cr, lecture), "CR with every delegable permission");
        assertThrows(AccessDeniedException.class, () -> feedback.report(asha, lecture));
        assertThrows(AccessDeniedException.class, () -> feedback.trend(cr, courseId));
    }
}
