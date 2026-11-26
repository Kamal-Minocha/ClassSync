package com.classsync.service;

import com.classsync.config.Database;
import com.classsync.config.Tx;
import com.classsync.dao.AuditDao;
import com.classsync.dao.CourseDao;
import com.classsync.dao.LectureDao;
import com.classsync.dao.NotificationDao;
import com.classsync.model.CoursePermission;
import com.classsync.model.Lecture;
import com.classsync.model.LectureStatus;
import com.classsync.model.User;
import com.classsync.service.AppException.AccessDeniedException;
import com.classsync.service.AppException.NotFoundException;
import com.classsync.service.AppException.ValidationException;
import com.classsync.util.Validation;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

/**
 * Lectures: create/update (professor or CR with UPDATE_LECTURES), status changes,
 * schedule queries and visibility (students never see DRAFT lectures).
 */
public class LectureService {

    /** Form values; date/times are in the academic time zone. lectureNo null = next number. */
    public record LectureForm(long courseId, Integer lectureNo, int unitNo, Long topicId, String topic,
                              String description, String objectives, String preparation, String room,
                              LocalDate date, LocalTime start, LocalTime end) {
    }

    private final Database db;
    private final AccessPolicy access;
    private final Clock clock;
    private final LectureDao lectures = new LectureDao();
    private final CourseDao courses = new CourseDao();
    private final AuditDao audit = new AuditDao();
    private final NotificationDao notifications = new NotificationDao();

    public LectureService(Database db, AccessPolicy access, Clock clock) {
        this.db = db;
        this.access = access;
        this.clock = clock;
    }

    public ZoneId zone() {
        return clock.getZone();
    }

    // ---------------------------------------------------------------- queries

    /** Lectures overlapping [from, to) in all of the user's courses. */
    public List<Lecture> schedule(User u, Instant from, Instant to) throws SQLException {
        try (Connection con = db.open()) {
            return lectures.list(con, access.visibleCourseIds(con, u), access.staffCourseIds(con, u),
                    from, to, true, 500);
        }
    }

    public List<Lecture> today(User u) throws SQLException {
        LocalDate day = LocalDate.now(clock);
        return schedule(u, day.atStartOfDay(zone()).toInstant(), day.plusDays(1).atStartOfDay(zone()).toInstant());
    }

    /** Upcoming lectures (not yet ended), soonest first. Cancelled ones are included so students see them. */
    public List<Lecture> upcoming(User u, int limit) throws SQLException {
        try (Connection con = db.open()) {
            return lectures.list(con, access.visibleCourseIds(con, u), access.staffCourseIds(con, u),
                    clock.instant(), null, true, limit);
        }
    }

    public List<Lecture> previous(User u, int limit) throws SQLException {
        try (Connection con = db.open()) {
            return lectures.list(con, access.visibleCourseIds(con, u), access.staffCourseIds(con, u),
                    null, clock.instant(), false, limit);
        }
    }

    public List<Lecture> forCourse(User u, long courseId) throws SQLException {
        try (Connection con = db.open()) {
            access.requireMember(con, u, courseId);
            boolean staff = access.has(con, u, courseId, CoursePermission.VIEW_SCHEDULE);
            return lectures.list(con, List.of(courseId), staff ? List.of(courseId) : List.of(), null, null, true, 500);
        }
    }

    public List<Lecture> search(User u, String query, int limit) throws SQLException {
        String q = Validation.text(query, "Search text", 2, 100);
        try (Connection con = db.open()) {
            return lectures.search(con, access.visibleCourseIds(con, u), access.staffCourseIds(con, u), q, limit);
        }
    }

    /** One lecture, re-checking membership and draft visibility (ids from the UI are untrusted). */
    public Lecture get(User u, long lectureId) throws SQLException {
        try (Connection con = db.open()) {
            return visibleLecture(con, u, lectureId);
        }
    }

    Lecture visibleLecture(Connection con, User u, long lectureId) throws SQLException {
        Lecture l = lectures.findById(con, lectureId).orElseThrow(() -> new NotFoundException("Lecture not found."));
        if (!access.isMember(con, u, l.courseId())) {
            throw new NotFoundException("Lecture not found.");
        }
        if (l.status() == LectureStatus.DRAFT && !access.has(con, u, l.courseId(), CoursePermission.VIEW_SCHEDULE)) {
            throw new NotFoundException("Lecture not found.");
        }
        return l;
    }

    public int nextLectureNo(User u, long courseId) throws SQLException {
        try (Connection con = db.open()) {
            access.requireMember(con, u, courseId);
            return lectures.nextLectureNo(con, courseId);
        }
    }

    // ---------------------------------------------------------------- changes

    public long create(User u, LectureForm f) throws SQLException {
        try {
            return Tx.inTransaction(db, con -> {
                access.require(con, u, f.courseId(), CoursePermission.UPDATE_LECTURES);
                requireActiveCourse(con, f.courseId());
                LectureDao.LectureData data = validate(con, f);
                long id = lectures.insert(con, data, u.id());
                audit.log(con, u.id(), "LECTURE_CREATE", "lecture", id, "L" + data.lectureNo());
                return id;
            });
        } catch (SQLException e) {
            throw translate(e);
        }
    }

    public void update(User u, long lectureId, LectureForm f) throws SQLException {
        try {
            Tx.inTransaction(db, con -> {
                Lecture current = lectures.lockById(con, lectureId).orElseThrow(() -> new NotFoundException("Lecture not found."));
                if (current.courseId() != f.courseId()) {
                    throw new ValidationException("A lecture cannot be moved to another course.");
                }
                access.require(con, u, current.courseId(), CoursePermission.UPDATE_LECTURES);
                if (current.status() == LectureStatus.COMPLETED || current.status() == LectureStatus.CANCELLED) {
                    throw new ValidationException("Completed or cancelled lectures cannot be edited.");
                }
                LectureDao.LectureData data = validate(con, f);
                lectures.update(con, lectureId, data);
                audit.log(con, u.id(), "LECTURE_UPDATE", "lecture", lectureId, "");
                if (current.status() == LectureStatus.PUBLISHED && !data.room().isBlank()
                        && !data.room().equalsIgnoreCase(current.room())) {
                    notifications.notifyCourseLearners(con, current.courseId(), u.id(), new NotificationDao.Message("LECTURE",
                            "Room change: " + current.courseCode() + " L" + current.lectureNo() + " is now in " + data.room(),
                            current.topic(), "LECTURE", lectureId, "lecture-room:" + lectureId + ":" + data.room().toUpperCase()));
                }
                return null;
            });
        } catch (SQLException e) {
            throw translate(e);
        }
    }

    /**
     * Status transitions follow LectureStatus.next(). Publishing/unpublishing needs UPDATE_LECTURES
     * (so a delegated CR may do it); completing or cancelling is reserved for the professor.
     */
    public void changeStatus(User u, long lectureId, LectureStatus next) throws SQLException {
        Tx.inTransaction(db, con -> {
            Lecture l = lectures.lockById(con, lectureId).orElseThrow(() -> new NotFoundException("Lecture not found."));
            if (next == LectureStatus.COMPLETED || next == LectureStatus.CANCELLED || l.status() == LectureStatus.CANCELLED) {
                access.requireCourseProfessor(con, u, l.courseId());
            } else {
                access.require(con, u, l.courseId(), CoursePermission.UPDATE_LECTURES);
            }
            if (!l.status().next().contains(next)) {
                throw new ValidationException("A " + l.status().label().toLowerCase() + " lecture cannot become "
                        + next.label().toLowerCase() + ".");
            }
            if (l.attendanceFinalized() && next == LectureStatus.CANCELLED) {
                throw new ValidationException("Attendance for this lecture is finalized; it cannot be cancelled.");
            }
            if (!lectures.changeStatus(con, lectureId, l.status(), next)) {
                throw new ValidationException("The lecture was changed by someone else. Refresh and try again.");
            }
            audit.log(con, u.id(), "LECTURE_STATUS", "lecture", lectureId, l.status() + " -> " + next);
            if (next == LectureStatus.CANCELLED && l.status() != LectureStatus.DRAFT) {
                notifications.notifyCourseLearners(con, l.courseId(), u.id(), new NotificationDao.Message("LECTURE",
                        "Cancelled: " + l.courseCode() + " L" + l.lectureNo() + " · " + l.topic(),
                        "This lecture will not take place.", "LECTURE", lectureId, "lecture-cancelled:" + lectureId));
            }
            return null;
        });
    }

    /** Deletes a DRAFT lecture that has no dependent records; otherwise cancel it instead. */
    public void deleteDraft(User u, long lectureId) throws SQLException {
        try {
            Tx.inTransaction(db, con -> {
                Lecture l = lectures.lockById(con, lectureId).orElseThrow(() -> new NotFoundException("Lecture not found."));
                access.requireCourseProfessor(con, u, l.courseId());
                if (l.status() != LectureStatus.DRAFT) {
                    throw new ValidationException("Only draft lectures can be deleted. Cancel it instead.");
                }
                lectures.deleteDraft(con, lectureId);
                audit.log(con, u.id(), "LECTURE_DELETE", "lecture", lectureId, "L" + l.lectureNo());
                return null;
            });
        } catch (SQLException e) {
            if ("23503".equals(e.getSQLState())) {
                throw new ValidationException("This lecture already has materials or other records. Cancel it instead of deleting.");
            }
            throw e;
        }
    }

    public void setFeedbackSettings(User u, long lectureId, boolean open, boolean anonymous) throws SQLException {
        Tx.inTransaction(db, con -> {
            Lecture l = lectures.lockById(con, lectureId).orElseThrow(() -> new NotFoundException("Lecture not found."));
            access.requireCourseProfessor(con, u, l.courseId());
            lectures.setFeedbackSettings(con, lectureId, open, anonymous);
            audit.log(con, u.id(), "FEEDBACK_SETTINGS", "lecture", lectureId, "open=" + open + " anonymous=" + anonymous);
            return null;
        });
    }

    // ---------------------------------------------------------------- helpers

    private void requireActiveCourse(Connection con, long courseId) throws SQLException {
        var c = courses.findById(con, courseId).orElseThrow(() -> new NotFoundException("Course not found."));
        if (c.archived()) {
            throw new AccessDeniedException("This course is archived.");
        }
    }

    private LectureDao.LectureData validate(Connection con, LectureForm f) throws SQLException {
        String topic = Validation.text(f.topic(), "Topic", 2, 150);
        String description = Validation.optional(f.description(), "Description", 4000);
        String objectives = Validation.optional(f.objectives(), "Objectives", 4000);
        String preparation = Validation.optional(f.preparation(), "Preparation instructions", 4000);
        String room = Validation.optional(f.room(), "Room", 40);
        int unit = Validation.range(f.unitNo(), "Unit", 1, 10);
        if (f.date() == null || f.start() == null || f.end() == null) {
            throw new ValidationException("Date, start time and end time are required.");
        }
        Instant start = f.date().atTime(f.start()).atZone(zone()).toInstant();
        Instant end = f.date().atTime(f.end()).atZone(zone()).toInstant();
        if (!end.isAfter(start)) {
            throw new ValidationException("The end time must be after the start time.");
        }
        if (Duration.between(start, end).toHours() > 6) {
            throw new ValidationException("A lecture can be at most 6 hours long.");
        }
        int number = f.lectureNo() == null ? lectures.nextLectureNo(con, f.courseId())
                : Validation.range(f.lectureNo(), "Lecture number", 1, 500);
        if (f.topicId() != null && courses.listTopics(con, f.courseId()).stream().noneMatch(t -> t.id() == f.topicId())) {
            throw new ValidationException("The selected topic does not belong to this course.");
        }
        return new LectureDao.LectureData(f.courseId(), number, unit, f.topicId(), topic, description, objectives,
                preparation, room, start, end);
    }

    private static SQLException translate(SQLException e) {
        if ("23505".equals(e.getSQLState())) {
            throw new ValidationException("Another lecture in this course already uses that lecture number.");
        }
        return e;
    }
}
