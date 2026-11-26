package com.classsync.service;

import com.classsync.config.Database;
import com.classsync.config.Tx;
import com.classsync.dao.AuditDao;
import com.classsync.dao.CourseDao;
import com.classsync.dao.DiscussionDao;
import com.classsync.dao.NotificationDao;
import com.classsync.model.CoursePermission;
import com.classsync.model.Discussion;
import com.classsync.model.DiscussionReply;
import com.classsync.model.Lecture;
import com.classsync.model.User;
import com.classsync.service.AppException.AccessDeniedException;
import com.classsync.service.AppException.NotFoundException;
import com.classsync.service.AppException.ValidationException;
import com.classsync.util.Validation;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Doubt forum. Every member of a course (students, CRs, the professor) can read, ask and reply.
 *
 * Resolve / reopen: the doubt's author, the course professor, or a CR explicitly delegated
 * MODERATE_DISCUSSIONS. Hide / unhide (moderation): the course professor or a delegated CR only.
 * Hidden items stay in the database; only moderators still see them.
 */
public class DiscussionService {

    public record DoubtForm(long courseId, Long lectureId, String title, String body) {
    }

    public record Query(Long courseId, Long lectureId, String text, boolean unresolvedOnly, boolean mineOnly, int limit) {
    }

    /** A doubt with its replies and what the viewer may do. */
    public record Thread(Discussion discussion, List<DiscussionReply> replies, boolean canResolve, boolean canModerate) {
    }

    private final Database db;
    private final AccessPolicy access;
    private final LectureService lectures;
    private final DiscussionDao dao = new DiscussionDao();
    private final CourseDao courses = new CourseDao();
    private final NotificationDao notifications = new NotificationDao();
    private final AuditDao audit = new AuditDao();

    public DiscussionService(Database db, AccessPolicy access, LectureService lectures) {
        this.db = db;
        this.access = access;
        this.lectures = lectures;
    }

    public long ask(User u, DoubtForm f) throws SQLException {
        String title = Validation.text(f.title(), "Question", 5, 200);
        String body = Validation.optional(f.body(), "Details", 4000);
        return Tx.inTransaction(db, con -> {
            access.requireMember(con, u, f.courseId());
            requireActive(con, f.courseId());
            Long topicId = null;
            if (f.lectureId() != null) {
                Lecture l = lectures.visibleLecture(con, u, f.lectureId());
                if (l.courseId() != f.courseId()) {
                    throw new ValidationException("The lecture belongs to a different course.");
                }
                topicId = l.topicId();
            }
            long id = dao.insert(con, f.courseId(), f.lectureId(), topicId, u.id(), title, body);
            audit.log(con, u.id(), "DOUBT_ASK", "discussion", id, "");
            return id;
        });
    }

    public List<Discussion> list(User u, Query q) throws SQLException {
        String text = q.text() == null ? null : Validation.optional(q.text(), "Search text", 100);
        try (Connection con = db.open()) {
            List<Long> visible = access.visibleCourseIds(con, u);
            if (q.courseId() != null) {
                visible = visible.contains(q.courseId()) ? List.of(q.courseId()) : List.of();
            }
            List<Long> moderated = new ArrayList<>();
            for (Long id : visible) {
                if (canModerate(con, u, id)) {
                    moderated.add(id);
                }
            }
            return dao.list(con, new DiscussionDao.Filter(visible, moderated, q.lectureId(), text, q.unresolvedOnly(),
                    q.mineOnly() ? u.id() : null, Math.min(Math.max(q.limit(), 1), 300)));
        }
    }

    public Thread thread(User u, long discussionId) throws SQLException {
        try (Connection con = db.open()) {
            Discussion d = visible(con, u, discussionId);
            boolean moderate = canModerate(con, u, d.courseId());
            long professorId = courses.findById(con, d.courseId()).orElseThrow().professorId();
            return new Thread(d, dao.replies(con, discussionId, professorId, u.id(), moderate),
                    d.authorId() == u.id() || moderate, moderate);
        }
    }

    /** Replies and notifies the doubt's author (once per reply, never for one's own reply). */
    public long reply(User u, long discussionId, String body) throws SQLException {
        String text = Validation.text(body, "Reply", 2, 4000);
        return Tx.inTransaction(db, con -> {
            Discussion d = visible(con, u, discussionId);
            if (d.hidden()) {
                throw new ValidationException("This doubt was hidden by a moderator.");
            }
            requireActive(con, d.courseId());
            long id = dao.insertReply(con, discussionId, u.id(), text);
            if (d.authorId() != u.id()) {
                notifications.notifyUsers(con, List.of(d.authorId()), new NotificationDao.Message("DISCUSSION",
                        u.fullName() + " replied to your doubt", d.title(), "DISCUSSION", discussionId, "reply:" + id));
            }
            return id;
        });
    }

    public void setResolved(User u, long discussionId, boolean resolved) throws SQLException {
        Tx.inTransaction(db, con -> {
            Discussion d = dao.lockById(con, discussionId).orElseThrow(() -> new NotFoundException("Doubt not found."));
            access.requireMember(con, u, d.courseId());
            if (d.authorId() != u.id() && !canModerate(con, u, d.courseId())) {
                throw new AccessDeniedException("Only the person who asked, the professor or a delegated moderator can resolve this doubt.");
            }
            if (d.resolved() == resolved) {
                return null;
            }
            dao.setResolved(con, discussionId, resolved ? u.id() : null);
            audit.log(con, u.id(), resolved ? "DOUBT_RESOLVE" : "DOUBT_REOPEN", "discussion", discussionId, "");
            if (resolved && d.authorId() != u.id()) {
                notifications.notifyUsers(con, List.of(d.authorId()), new NotificationDao.Message("DISCUSSION",
                        "Your doubt was marked resolved", d.title(), "DISCUSSION", discussionId, "discussion-resolved:" + discussionId));
            }
            return null;
        });
    }

    /** Moderation: hide or unhide a doubt. */
    public void setDoubtHidden(User u, long discussionId, boolean hidden) throws SQLException {
        Tx.inTransaction(db, con -> {
            Discussion d = dao.lockById(con, discussionId).orElseThrow(() -> new NotFoundException("Doubt not found."));
            requireModerator(con, u, d.courseId());
            dao.setHidden(con, false, discussionId, hidden ? u.id() : null);
            audit.log(con, u.id(), hidden ? "DOUBT_HIDE" : "DOUBT_UNHIDE", "discussion", discussionId, "");
            return null;
        });
    }

    /** Moderation: hide or unhide a reply. */
    public void setReplyHidden(User u, long replyId, boolean hidden) throws SQLException {
        Tx.inTransaction(db, con -> {
            long[] owner = dao.replyOwner(con, replyId).orElseThrow(() -> new NotFoundException("Reply not found."));
            Discussion d = dao.findById(con, owner[0]).orElseThrow();
            requireModerator(con, u, d.courseId());
            dao.setHidden(con, true, replyId, hidden ? u.id() : null);
            audit.log(con, u.id(), hidden ? "REPLY_HIDE" : "REPLY_UNHIDE", "discussion", d.id(), "reply " + replyId);
            return null;
        });
    }

    /** One upvote per person per reply (primary key); not on one's own reply. */
    public void voteReply(User u, long replyId, boolean up) throws SQLException {
        Tx.inTransaction(db, con -> {
            long[] owner = dao.replyOwner(con, replyId).orElseThrow(() -> new NotFoundException("Reply not found."));
            visible(con, u, owner[0]);
            if (owner[1] == u.id()) {
                throw new ValidationException("You cannot upvote your own reply.");
            }
            if (up) {
                dao.vote(con, replyId, u.id());
            } else {
                dao.unvote(con, replyId, u.id());
            }
            return null;
        });
    }

    /** Doubt Heatmap data: visible doubts per syllabus topic, most unresolved first (any course member). */
    public List<DiscussionDao.HeatRow> heatmap(User u, long courseId) throws SQLException {
        try (Connection con = db.open()) {
            access.requireMember(con, u, courseId);
            return dao.heatmap(con, courseId);
        }
    }

    // ---------------------------------------------------------------- helpers

    boolean canModerate(Connection con, User u, long courseId) throws SQLException {
        return access.has(con, u, courseId, CoursePermission.MODERATE_DISCUSSIONS);
    }

    private void requireModerator(Connection con, User u, long courseId) throws SQLException {
        if (!canModerate(con, u, courseId)) {
            throw new AccessDeniedException("Only the professor or a CR with moderation rights can do this.");
        }
    }

    /** Membership check; hidden doubts are "not found" for non-moderators. */
    private Discussion visible(Connection con, User u, long id) throws SQLException {
        Discussion d = dao.findById(con, id).orElseThrow(() -> new NotFoundException("Doubt not found."));
        if (!access.isMember(con, u, d.courseId()) || (d.hidden() && !canModerate(con, u, d.courseId()))) {
            throw new NotFoundException("Doubt not found.");
        }
        return d;
    }

    private void requireActive(Connection con, long courseId) throws SQLException {
        if (courses.findById(con, courseId).orElseThrow().archived()) {
            throw new AccessDeniedException("This course is archived.");
        }
    }
}
