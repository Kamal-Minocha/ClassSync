package com.classsync.service;

import com.classsync.Fixtures;
import com.classsync.TestDb;
import com.classsync.config.Database;
import com.classsync.dao.CourseDao;
import com.classsync.model.AttemptMode;
import com.classsync.model.LectureStatus;
import com.classsync.model.QuestionKind;
import com.classsync.model.ReviewPolicy;
import com.classsync.model.User;
import com.classsync.service.AppException.AccessDeniedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Revision mode, performance, streaks and badges on real data. */
class ProgressServiceIT {

    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    private Database db;
    private Fixtures fx;
    private ProgressService progress;
    private QuizService quizzes;
    private User prof;
    private User asha;
    private User neha;
    private User outsider;
    private long courseId;
    private long topicId;

    @BeforeEach
    void setUp() throws SQLException {
        db = TestDb.reset();
        fx = new Fixtures(db);
        AccessPolicy access = new AccessPolicy();
        Clock clock = Clock.system(ZONE);
        quizzes = new QuizService(db, access, clock);
        QuizAnalyticsService analytics = new QuizAnalyticsService(db, access, quizzes);
        progress = new ProgressService(db, access, clock, new AttendanceService(db, access, 120), analytics);
        prof = fx.professor("Prof");
        asha = fx.student("Asha");
        neha = fx.student("Neha");
        outsider = fx.student("Outsider");
        courseId = fx.course(prof, "CS301");
        fx.enroll(courseId, asha, neha);
        try (Connection con = db.open()) {
            topicId = new CourseDao().insertTopic(con, courseId, 2, "Joins", 1);
        }
    }

    private void sql(String sql, Object... args) throws SQLException {
        try (Connection con = db.open(); PreparedStatement ps = con.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.executeUpdate();
        }
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

    /** Open quiz with two 1-mark questions (correct = "Right"). */
    private long quiz(ReviewPolicy policy) throws SQLException {
        ZonedDateTime o = Instant.now().minus(1, ChronoUnit.HOURS).atZone(ZONE);
        ZonedDateTime c = Instant.now().plus(1, ChronoUnit.HOURS).atZone(ZONE);
        long q = quizzes.create(prof, new QuizService.QuizForm(courseId, null, "Joins check", "", o.toLocalDate(), o.toLocalTime(),
                c.toLocalDate(), c.toLocalTime(), 10, policy, true));
        for (int i = 1; i <= 2; i++) {
            quizzes.addQuestion(prof, q, new QuizService.QuestionForm(QuestionKind.MCQ, "Question " + i, 1, topicId,
                    "Because " + i, List.of("Right", "Wrong"), 0));
        }
        quizzes.publish(prof, q);
        return q;
    }

    private void take(User u, long quiz, AttemptMode mode, String pattern) throws SQLException {
        long a = quizzes.start(u, quiz, mode);
        var qs = quizzes.session(u, a).questions();
        for (int i = 0; i < pattern.length(); i++) {
            if (pattern.charAt(i) != '-') {
                String text = pattern.charAt(i) == 'R' ? "Right" : "Wrong";
                quizzes.saveAnswer(u, a, qs.get(i).id(), qs.get(i).choices().stream().filter(c -> c.text().equals(text)).findFirst().orElseThrow().id());
            }
        }
        quizzes.submit(u, a);
    }

    @Test
    void activityIsDerivedFromRecordsAndViewingAddsNothing() throws SQLException {
        assertEquals(0, progress.recentActivity(asha, 50).size());
        long q = quiz(ReviewPolicy.AFTER_SUBMIT);
        take(asha, q, AttemptMode.OFFICIAL, "RR");
        progress.markRevised(asha, topicId, "Re-read chapter 4");
        int before = progress.recentActivity(asha, 50).size();
        assertEquals(2, before);
        for (int i = 0; i < 3; i++) {
            progress.performance(asha);   // "reopening the screen"
        }
        assertEquals(before, progress.recentActivity(asha, 50).size(), "viewing never creates activity");
        assertEquals(1, progress.streak(asha)[0]);
    }

    @Test
    void activityDaysUseTheAcademicTimeZone() throws SQLException {
        progress.markRevised(asha, topicId, "");
        // 20:00 UTC on 1 Jan is already 2 Jan in Asia/Kolkata (+05:30)
        sql("UPDATE revision_history SET revised_at = '2026-01-01T20:00:00Z'");
        var row = progress.recentActivity(asha, 5).get(0);
        assertEquals(java.time.LocalDate.of(2026, 1, 2), row.day());
    }

    @Test
    void streakBadgeIsAwardedOnceForFiveConsecutiveDays() throws SQLException {
        progress.markRevised(asha, topicId, "");
        try (Connection con = db.open()) {
            long t2 = new CourseDao().insertTopic(con, courseId, 2, "Subqueries", 2);
            for (int i = 1; i <= 4; i++) {
                try (PreparedStatement ps = con.prepareStatement(
                        "INSERT INTO revision_history (student_id, topic_id, revised_on, revised_at) VALUES (?, ?, current_date - ?, now() - make_interval(days => ?))")) {
                    ps.setLong(1, asha.id());
                    ps.setLong(2, t2);
                    ps.setInt(3, i);
                    ps.setInt(4, i);
                    ps.executeUpdate();
                }
            }
        }
        var p = progress.performance(asha);
        assertEquals(5, p.longestStreak());
        assertTrue(p.newlyAwarded().contains("FIVE_DAY_STREAK"));
        assertTrue(progress.evaluateBadges(asha).isEmpty(), "evaluating again awards nothing new");
        assertEquals(1, count("SELECT count(*) FROM student_achievements WHERE student_id = ? AND achievement_code = 'FIVE_DAY_STREAK'", asha.id()));
    }

    @Test
    void quizBadgesRespectResultReleaseAndNeverChangeMarks() throws SQLException {
        long q = quiz(ReviewPolicy.AFTER_CLOSE);
        take(asha, q, AttemptMode.OFFICIAL, "RR");
        var codes = progress.evaluateBadges(asha);
        assertTrue(codes.contains("FIRST_QUIZ"));
        assertFalse(codes.contains("PERFECT_SCORE"), "the perfect score is still secret until the quiz closes");
        assertTrue(progress.performance(asha).scores().isEmpty(), "unreleased scores are not shown");
        sql("UPDATE quizzes SET opens_at = now() - interval '3 hours', closes_at = now() - interval '1 minute' WHERE id = ?", q);
        assertEquals(List.of("PERFECT_SCORE"), progress.evaluateBadges(asha));
        var p = progress.performance(asha);
        assertEquals(1.0, p.quizAverage().orElseThrow(), 1e-9);
        assertEquals(2, count("SELECT score FROM quiz_attempts WHERE student_id = ?", asha.id()), "marks untouched");
    }

    @Test
    void practiceAttemptsStayOutOfPerformance() throws SQLException {
        long q = quiz(ReviewPolicy.AFTER_SUBMIT);
        take(asha, q, AttemptMode.OFFICIAL, "RW");
        take(asha, q, AttemptMode.PRACTICE, "RR");
        var p = progress.performance(asha);
        assertEquals(1, p.scores().size());
        assertEquals(0.5, p.quizAverage().orElseThrow(), 1e-9);
        assertFalse(p.badges().stream().anyMatch(b -> b.code().equals("PERFECT_SCORE") && b.awardedAt() != null),
                "a perfect PRACTICE attempt earns no badge");
    }

    @Test
    void contributionBadgesUseTheirThresholds() throws SQLException {
        var forum = new DiscussionService(db, new AccessPolicy(), new LectureService(db, new AccessPolicy(), Clock.system(ZONE)));
        long d = forum.ask(neha, new DiscussionService.DoubtForm(courseId, null, "What is a join?", ""));
        long r = forum.reply(asha, d, "It combines rows from two tables");
        List<User> voters = new java.util.ArrayList<>(List.of(neha, prof));
        for (int i = 0; i < 2; i++) {
            User s = fx.student("Voter " + i);
            fx.enroll(courseId, s);
            voters.add(s);
        }
        for (User v : voters) {
            forum.voteReply(v, r, true);
        }
        assertFalse(progress.evaluateBadges(asha).contains("HELPFUL_CONTRIBUTOR"), "4 upvotes < 5");
        User fifth = fx.student("Voter 5");
        fx.enroll(courseId, fifth);
        forum.voteReply(fifth, r, true);
        assertTrue(progress.evaluateBadges(asha).contains("HELPFUL_CONTRIBUTOR"));

        for (int i = 0; i < 3; i++) {
            sql("""
                    INSERT INTO materials (course_id, kind, title, uploader_id, original_filename, stored_path, file_type,
                        size_bytes, published, review_status)
                    VALUES (?, 'NOTE', ?, ?, 'n.pdf', ?, 'pdf', 10, ?, ?)""",
                    courseId, "Note " + i, asha.id(), "notes/n" + i + ".pdf", i < 2, i < 2 ? "APPROVED" : "PENDING");
        }
        assertFalse(progress.evaluateBadges(asha).contains("NOTES_CHAMPION"), "only 2 approved");
        sql("UPDATE materials SET review_status = 'APPROVED', published = TRUE WHERE title = 'Note 2'");
        assertTrue(progress.evaluateBadges(asha).contains("NOTES_CHAMPION"));
    }

    @Test
    void mistakesShowOnlyReleasedWrongOrBlankAnswers() throws SQLException {
        long manual = quiz(ReviewPolicy.MANUAL);
        take(asha, manual, AttemptMode.OFFICIAL, "W-");
        assertTrue(progress.mistakes(asha).isEmpty(), "not released yet");
        quizzes.releaseResults(prof, manual);
        var m = progress.mistakes(asha);
        assertEquals(2, m.size(), "one wrong + one unanswered");
        assertEquals("Wrong", m.get(0).chosen());
        assertEquals("Right", m.get(0).correct());
        assertEquals("Because 1", m.get(0).explanation());
        assertNull(m.get(1).chosen());
        long other = quiz(ReviewPolicy.AFTER_SUBMIT);
        take(asha, other, AttemptMode.OFFICIAL, "RR");
        assertEquals(2, progress.mistakes(asha).size(), "correct answers never appear");
    }

    @Test
    void revisionIsOncePerTopicPerDayAndOnlyForMembers() throws SQLException {
        assertTrue(progress.markRevised(asha, topicId, "Practised 10 queries"));
        assertFalse(progress.markRevised(asha, topicId, "again"), "same day → no duplicate");
        assertEquals(1, progress.revisions(asha, 10).size());
        assertThrows(AccessDeniedException.class, () -> progress.markRevised(outsider, topicId, ""));
        assertThrows(AccessDeniedException.class, () -> progress.markRevised(prof, topicId, ""));
        assertTrue(progress.revisions(neha, 10).isEmpty(), "revision history is personal");
        assertThrows(AccessDeniedException.class, () -> progress.performance(prof));
        assertEquals(LectureStatus.COMPLETED, LectureStatus.COMPLETED);
    }
}
