package com.classsync.service;

import com.classsync.Fixtures;
import com.classsync.TestDb;
import com.classsync.config.Database;
import com.classsync.dao.CourseDao;
import com.classsync.dao.DiscussionDao;
import com.classsync.model.CoursePermission;
import com.classsync.model.LectureStatus;
import com.classsync.model.User;
import com.classsync.service.AppException.AccessDeniedException;
import com.classsync.service.AppException.NotFoundException;
import com.classsync.service.AppException.ValidationException;
import com.classsync.service.DiscussionService.DoubtForm;
import com.classsync.service.DiscussionService.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.ZoneId;
import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.*;

/** Doubt forum: posting, visibility, resolve rights, moderation, unique votes, search and heatmap. */
class DiscussionServiceIT {

    private Database db;
    private Fixtures fx;
    private DiscussionService forum;
    private NotificationService notifications;
    private CourseService courses;
    private User prof;
    private User asha;
    private User neha;
    private User cr;
    private User outsider;
    private long courseId;
    private long lectureJoins;
    private long topicJoins;

    @BeforeEach
    void setUp() throws SQLException {
        db = TestDb.reset();
        fx = new Fixtures(db);
        AccessPolicy access = new AccessPolicy();
        LectureService lectures = new LectureService(db, access, Clock.system(ZoneId.of("Asia/Kolkata")));
        forum = new DiscussionService(db, access, lectures);
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
        lectureJoins = fx.lecture(courseId, prof, 3, -24, LectureStatus.COMPLETED);
        try (Connection con = db.open()) {
            topicJoins = new CourseDao().insertTopic(con, courseId, 2, "Joins", 1);
            try (PreparedStatement ps = con.prepareStatement("UPDATE lectures SET topic_id = ? WHERE id = ?")) {
                ps.setLong(1, topicJoins);
                ps.setLong(2, lectureJoins);
                ps.executeUpdate();
            }
        }
    }

    private long ask(User u, String title, Long lecture) throws SQLException {
        return forum.ask(u, new DoubtForm(courseId, lecture, title, "details"));
    }

    @Test
    void askReplyAndNotifyTheAuthorOnce() throws SQLException {
        long d = ask(asha, "Why does LEFT JOIN return NULLs?", lectureJoins);
        forum.reply(asha, d, "Adding context to my own doubt");
        assertEquals(0, notifications.unreadCount(asha), "no notification for one's own reply");
        forum.reply(neha, d, "Because unmatched rows have no partner");
        forum.reply(prof, d, "Exactly — the right side is filled with NULLs.");
        assertEquals(2, notifications.unreadCount(asha), "one per reply by someone else");

        var thread = forum.thread(neha, d);
        assertEquals(3, thread.replies().size());
        assertTrue(thread.replies().get(2).professorAnswer(), "professor answers are marked");
        assertTrue(thread.discussion().professorAnswered());
        assertEquals("Joins", thread.discussion().topicName(), "topic taken from the lecture");
        assertFalse(thread.canResolve(), "Neha is neither author nor moderator");
    }

    @Test
    void outsidersCannotReadOrPost() throws SQLException {
        long d = ask(asha, "What is a natural join?", null);
        assertThrows(AccessDeniedException.class, () -> ask(outsider, "Can I join?", null));
        assertThrows(NotFoundException.class, () -> forum.thread(outsider, d));
        assertThrows(NotFoundException.class, () -> forum.reply(outsider, d, "Hello there"));
        assertTrue(forum.list(outsider, new Query(null, null, null, false, false, 50)).isEmpty());
        assertTrue(forum.list(outsider, new Query(courseId, null, null, false, false, 50)).isEmpty());
    }

    @Test
    void resolveRightsAreAuthorProfessorOrDelegatedModerator() throws SQLException {
        long d = ask(asha, "How do subqueries work?", null);
        assertThrows(AccessDeniedException.class, () -> forum.setResolved(neha, d, true));
        assertThrows(AccessDeniedException.class, () -> forum.setResolved(cr, d, true), "CR without explicit moderation");
        courses.setStaffPermissions(prof, courseId, cr.id(), EnumSet.of(CoursePermission.MODERATE_DISCUSSIONS));
        forum.setResolved(cr, d, true);
        assertTrue(forum.thread(asha, d).discussion().resolved());
        assertEquals(1, notifications.unreadCount(asha), "author told when someone else resolves");
        forum.setResolved(asha, d, false);
        forum.setResolved(prof, d, true);
        forum.setResolved(prof, d, true);   // repeating is harmless
        assertEquals("Prof", forum.thread(asha, d).discussion().resolvedByName());
    }

    @Test
    void moderationHidesContentFromStudentsButNotModerators() throws SQLException {
        long d = ask(asha, "Off-topic question here", null);
        long r = forum.reply(neha, d, "Spam reply");
        assertThrows(AccessDeniedException.class, () -> forum.setReplyHidden(asha, r, true), "authors are not moderators");
        forum.setReplyHidden(prof, r, true);
        assertTrue(forum.thread(asha, d).replies().isEmpty());
        assertEquals(1, forum.thread(prof, d).replies().size(), "moderator still sees it, flagged");
        assertTrue(forum.thread(prof, d).replies().get(0).hidden());

        forum.setDoubtHidden(prof, d, true);
        assertThrows(NotFoundException.class, () -> forum.thread(asha, d));
        assertTrue(forum.list(asha, new Query(courseId, null, null, false, false, 50)).isEmpty());
        assertEquals(1, forum.list(prof, new Query(courseId, null, null, false, false, 50)).size());
        assertThrows(NotFoundException.class, () -> forum.reply(neha, d, "still here?"));
        courses.setStaffPermissions(prof, courseId, cr.id(), EnumSet.of(CoursePermission.MODERATE_DISCUSSIONS));
        forum.setDoubtHidden(cr, d, false);
        assertEquals(1, forum.list(asha, new Query(courseId, null, null, false, false, 50)).size());
    }

    @Test
    void replyVotesAreUniqueAndNotForOwnReplies() throws SQLException {
        long d = ask(asha, "Explain GROUP BY please", null);
        long r = forum.reply(neha, d, "It groups rows with equal values");
        forum.voteReply(asha, r, true);
        forum.voteReply(asha, r, true);
        forum.voteReply(cr, r, true);
        assertEquals(2, forum.thread(asha, d).replies().get(0).votes());
        assertTrue(forum.thread(asha, d).replies().get(0).votedByMe());
        assertThrows(ValidationException.class, () -> forum.voteReply(neha, r, true));
        forum.voteReply(asha, r, false);
        assertEquals(1, forum.thread(asha, d).replies().get(0).votes());
    }

    @Test
    void searchFiltersAndWildcardsAreLiteral() throws SQLException {
        ask(asha, "Is 100% of a join always kept?", null);
        long d2 = ask(neha, "What does a CROSS JOIN do?", null);
        forum.setResolved(neha, d2, true);
        assertEquals(1, forum.list(asha, new Query(courseId, null, "100%", false, false, 50)).size());
        assertEquals(1, forum.list(asha, new Query(courseId, null, "%", false, false, 50)).size(), "% matched literally");
        assertEquals(1, forum.list(asha, new Query(courseId, null, null, true, false, 50)).size(), "unresolved only");
        assertEquals(1, forum.list(neha, new Query(null, null, null, false, true, 50)).size(), "mine only");
    }

    @Test
    void heatmapCountsUnresolvedDoubtsPerTopic() throws SQLException {
        ask(asha, "Why NULLs in LEFT JOIN?", lectureJoins);
        ask(neha, "Self join example?", lectureJoins);
        long done = ask(cr, "Join vs subquery speed?", lectureJoins);
        forum.setResolved(cr, done, true);
        ask(asha, "Exam pattern question", null);
        var rows = forum.heatmap(asha, courseId);
        DiscussionDao.HeatRow joins = rows.get(0);
        assertEquals("Joins", joins.topic());
        assertEquals(2, joins.unresolved());
        assertEquals(3, joins.total());
        assertEquals("General (no topic)", rows.get(1).topic());
        assertThrows(AccessDeniedException.class, () -> forum.heatmap(outsider, courseId));
    }
}
