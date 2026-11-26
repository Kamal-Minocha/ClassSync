package com.classsync.service;

import com.classsync.config.Database;
import com.classsync.config.Tx;
import com.classsync.dao.AnnouncementDao;
import com.classsync.dao.AuditDao;
import com.classsync.dao.CourseDao;
import com.classsync.dao.NotificationDao;
import com.classsync.model.Announcement;
import com.classsync.model.AnnouncementCategory;
import com.classsync.model.CoursePermission;
import com.classsync.model.Course;
import com.classsync.model.Lecture;
import com.classsync.model.User;
import com.classsync.service.AppException.AccessDeniedException;
import com.classsync.service.AppException.ValidationException;
import com.classsync.util.Validation;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/**
 * Course announcements. Published by the course professor or a CR delegated PUBLISH_ANNOUNCEMENTS.
 * Audience: everyone enrolled in that course (and the professor, when a CR publishes). The announcement and
 * its notifications are written in ONE transaction; notification dedupe keys make retries harmless.
 */
public class AnnouncementService {

    public record AnnouncementForm(long courseId, Long lectureId, AnnouncementCategory category, String title, String body) {
    }

    private final Database db;
    private final AccessPolicy access;
    private final LectureService lectures;
    private final AnnouncementDao dao = new AnnouncementDao();
    private final CourseDao courses = new CourseDao();
    private final NotificationDao notifications = new NotificationDao();
    private final AuditDao audit = new AuditDao();

    public AnnouncementService(Database db, AccessPolicy access, LectureService lectures) {
        this.db = db;
        this.access = access;
        this.lectures = lectures;
    }

    public long publish(User u, AnnouncementForm f) throws SQLException {
        String title = Validation.text(f.title(), "Title", 3, 200);
        String body = Validation.optional(f.body(), "Message", 4000);
        if (f.category() == null) {
            throw new ValidationException("Choose a category.");
        }
        return Tx.inTransaction(db, con -> {
            access.require(con, u, f.courseId(), CoursePermission.PUBLISH_ANNOUNCEMENTS);
            Course c = courses.findById(con, f.courseId()).orElseThrow();
            if (c.archived()) {
                throw new AccessDeniedException("This course is archived.");
            }
            if (f.lectureId() != null) {
                Lecture l = lectures.visibleLecture(con, u, f.lectureId());
                if (l.courseId() != f.courseId()) {
                    throw new ValidationException("The lecture belongs to a different course.");
                }
            }
            long id = dao.insert(con, f.courseId(), f.lectureId(), u.id(), f.category(), title, body);
            NotificationDao.Message m = new NotificationDao.Message("ANNOUNCEMENT",
                    (f.category() == AnnouncementCategory.URGENT ? "URGENT · " : "") + c.code() + ": " + title,
                    body, "ANNOUNCEMENT", id, "announcement:" + id);
            notifications.notifyCourseLearners(con, f.courseId(), u.id(), m);
            if (c.professorId() != u.id()) {
                notifications.notifyUsers(con, List.of(c.professorId()), m);
            }
            audit.log(con, u.id(), "ANNOUNCEMENT_PUBLISH", "announcement", id, f.category().name());
            return id;
        });
    }

    /** Announcements in the user's courses (or one course), newest first. */
    public List<Announcement> list(User u, Long courseId, int limit) throws SQLException {
        try (Connection con = db.open()) {
            List<Long> visible = access.visibleCourseIds(con, u);
            if (courseId != null) {
                visible = visible.contains(courseId) ? List.of(courseId) : List.of();
            }
            return dao.list(con, visible, Math.min(Math.max(limit, 1), 300));
        }
    }

    public boolean canPublish(User u, long courseId) throws SQLException {
        try (Connection con = db.open()) {
            return access.has(con, u, courseId, CoursePermission.PUBLISH_ANNOUNCEMENTS);
        }
    }
}
