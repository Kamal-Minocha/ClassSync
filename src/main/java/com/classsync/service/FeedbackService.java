package com.classsync.service;

import com.classsync.config.Database;
import com.classsync.config.Tx;
import com.classsync.dao.FeedbackDao;
import com.classsync.dao.LectureDao;
import com.classsync.model.Lecture;
import com.classsync.model.LectureStatus;
import com.classsync.model.User;
import com.classsync.service.AppException.AccessDeniedException;
import com.classsync.service.AppException.NotFoundException;
import com.classsync.service.AppException.ValidationException;
import com.classsync.util.Validation;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.OptionalDouble;

/**
 * Lecture feedback (one per eligible student per lecture).
 *
 * Eligible: an enrolled student or CR, for a published or completed lecture that has started, while the
 * professor has feedback open. Edit policy: the student may change their own feedback while feedback stays
 * open; once the professor closes it, it is final.
 *
 * Anonymity: if the lecture collects anonymous feedback, the row is flagged anonymous and professor reports
 * show no name. Once a row is anonymous it stays anonymous, even if the professor later switches anonymity
 * off. No audit-log entry is written for feedback, so the log cannot reveal who gave it.
 * Limit (documented): the database still stores student_id to enforce one-per-student; database
 * administrators can technically join it back. Application users never can.
 */
public class FeedbackService {

    /** Ratings are 1 (lowest) to 5 (highest); for pace, 5 means "the pace was just right". */
    public record FeedbackForm(int understanding, int clarity, int pace, int usefulness, String difficult,
                               String suggestions) {
    }

    /** What the student sees for one lecture. */
    public record MyFeedback(Lecture lecture, boolean eligible, String reason, FeedbackDao.Own own) {
    }

    /** Professor report; averages are null when there are no submissions. */
    public record Report(Lecture lecture, int enrolled, FeedbackDao.Summary summary, List<FeedbackDao.Comment> comments) {

        public OptionalDouble participation() {
            return enrolled == 0 ? OptionalDouble.empty() : OptionalDouble.of((double) summary.count() / enrolled);
        }
    }

    private final Database db;
    private final AccessPolicy access;
    private final FeedbackDao dao = new FeedbackDao();
    private final LectureDao lectures = new LectureDao();
    private final com.classsync.dao.CourseDao courses = new com.classsync.dao.CourseDao();

    public FeedbackService(Database db, AccessPolicy access) {
        this.db = db;
        this.access = access;
    }

    /** The student's feedback for a lecture, and whether they may submit or edit it now. */
    public MyFeedback mine(User u, long lectureId) throws SQLException {
        try (Connection con = db.open()) {
            Lecture l = lectures.findById(con, lectureId).orElseThrow(() -> new NotFoundException("Lecture not found."));
            access.requireMember(con, u, l.courseId());
            String reason = ineligibility(con, u, l);
            return new MyFeedback(l, reason == null, reason, dao.findOwn(con, lectureId, u.id()).orElse(null));
        }
    }

    /** Submits or edits the student's feedback for a lecture. */
    public void submit(User u, long lectureId, FeedbackForm f) throws SQLException {
        FeedbackDao.Ratings r = new FeedbackDao.Ratings(
                Validation.range(f.understanding(), "Understanding", 1, 5), Validation.range(f.clarity(), "Clarity", 1, 5),
                Validation.range(f.pace(), "Pace", 1, 5), Validation.range(f.usefulness(), "Usefulness", 1, 5));
        String difficult = Validation.optional(f.difficult(), "Difficult concepts", 1000);
        String suggestions = Validation.optional(f.suggestions(), "Suggestions", 1000);
        try {
            Tx.inTransaction(db, con -> {
                // shared lock: the professor closing feedback (FOR UPDATE in LectureService) cannot interleave
                Lecture l = lectures.lockShared(con, lectureId).orElseThrow(() -> new NotFoundException("Lecture not found."));
                if (!access.isMember(con, u, l.courseId())) {
                    throw new AccessDeniedException("You are not enrolled in this course.");
                }
                String reason = ineligibility(con, u, l);
                if (reason != null) {
                    throw new ValidationException(reason);
                }
                var own = dao.findOwn(con, lectureId, u.id());
                if (own.isPresent()) {
                    dao.update(con, own.get().id(), own.get().anonymous() || l.feedbackAnonymous(), r, difficult, suggestions);
                } else {
                    dao.insert(con, lectureId, u.id(), l.feedbackAnonymous(), r, difficult, suggestions);
                }
                return null;
            });
        } catch (SQLException e) {
            if ("23505".equals(e.getSQLState())) {
                throw new ValidationException("Your feedback for this lecture was already saved. Refresh to edit it.");
            }
            throw e;
        }
    }

    /** Lectures open for feedback that the student has not answered yet. */
    public List<FeedbackDao.PendingRow> pending(User u) throws SQLException {
        if (!u.role().isLearner()) {
            return List.of();
        }
        try (Connection con = db.open()) {
            return dao.pending(con, u.id(), access.visibleCourseIds(con, u));
        }
    }

    /** Professor report for one lecture (no names for anonymous feedback). */
    public Report report(User u, long lectureId) throws SQLException {
        try (Connection con = db.open()) {
            Lecture l = lectures.findById(con, lectureId).orElseThrow(() -> new NotFoundException("Lecture not found."));
            access.requireCourseProfessor(con, u, l.courseId());
            return new Report(l, courses.enrolledCount(con, l.courseId()), dao.summary(con, lectureId), dao.comments(con, lectureId));
        }
    }

    /** Per-lecture averages for a course, oldest first. */
    public List<FeedbackDao.TrendRow> trend(User u, long courseId) throws SQLException {
        try (Connection con = db.open()) {
            access.requireCourseProfessor(con, u, courseId);
            return dao.trend(con, courseId);
        }
    }

    /** null when the user may submit now, otherwise the reason they cannot. */
    private String ineligibility(Connection con, User u, Lecture l) throws SQLException {
        if (!u.role().isLearner()) {
            return "Only students give lecture feedback.";
        }
        if (l.status() != LectureStatus.PUBLISHED && l.status() != LectureStatus.COMPLETED) {
            return "Feedback is not available for this lecture.";
        }
        Instant now = com.classsync.dao.DbTime.now(con);
        if (now.isBefore(l.startsAt())) {
            return "Feedback opens once the lecture has started.";
        }
        if (!l.feedbackOpen()) {
            return "Your professor has not opened feedback for this lecture (or has closed it).";
        }
        return null;
    }
}
