package com.classsync.service;

import com.classsync.config.Database;
import com.classsync.dao.CourseDao;
import com.classsync.dao.DbTime;
import com.classsync.dao.ProgressDao;
import com.classsync.model.Topic;
import com.classsync.model.User;
import com.classsync.service.AppException.AccessDeniedException;
import com.classsync.service.AppException.NotFoundException;
import com.classsync.util.Validation;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;

/**
 * Revision mode (Module 11) and performance & achievements (Module 12) for students and CRs.
 *
 * Meaningful activity = submitting a quiz, marking attendance with a code, giving feedback, submitting notes,
 * asking or answering a doubt, marking a topic revised, completing a study task. Days are counted in the
 * academic time zone. Viewing pages never counts.
 *
 * Badge rules (deterministic, awarded once, never revoked, never affect marks):
 *   FIRST_QUIZ          — at least one finished OFFICIAL attempt
 *   PERFECT_SCORE       — an OFFICIAL attempt with score = max score whose results are released
 *   FIVE_DAY_STREAK     — longest streak of consecutive active days ≥ 5
 *   HELPFUL_CONTRIBUTOR — ≥ 5 upvotes in total on the student's visible forum replies
 *   NOTES_CHAMPION      — ≥ 3 of the student's notes approved
 */
public class ProgressService {

    public static final int STREAK_BADGE_DAYS = 5;
    public static final int HELPFUL_UPVOTES = 5;
    public static final int CHAMPION_NOTES = 3;

    /** Performance overview. Only RELEASED official quiz results are included. */
    public record Performance(List<ProgressDao.ScoreRow> scores, OptionalDouble quizAverage,
                              AttendanceService.StudentSummary attendance, int currentStreak, int longestStreak,
                              Map<LocalDate, Integer> weekly, List<QuizAnalyticsService.TopicInsight> weakTopics,
                              List<ProgressDao.Badge> badges, List<String> newlyAwarded,
                              List<ProgressDao.ActivityRow> recent) {
    }

    private final Database db;
    private final AccessPolicy access;
    private final Clock clock;
    private final AttendanceService attendance;
    private final QuizAnalyticsService quizAnalytics;
    private final ProgressDao dao = new ProgressDao();
    private final CourseDao courses = new CourseDao();

    public ProgressService(Database db, AccessPolicy access, Clock clock, AttendanceService attendance,
                           QuizAnalyticsService quizAnalytics) {
        this.db = db;
        this.access = access;
        this.clock = clock;
        this.attendance = attendance;
        this.quizAnalytics = quizAnalytics;
    }

    // ---------------------------------------------------------------- revision mode

    /** Marks a syllabus topic revised today (academic zone). Returns false if already marked today. */
    public boolean markRevised(User u, long topicId, String note) throws SQLException {
        requireLearner(u);
        String n = Validation.optional(note, "Note", 500);
        try (Connection con = db.open()) {
            Topic t = topic(con, topicId);
            access.requireMember(con, u, t.courseId());
            LocalDate today = DbTime.now(con).atZone(clock.getZone()).toLocalDate();
            return dao.addRevision(con, u.id(), topicId, today, n);
        }
    }

    public List<ProgressDao.RevisionRow> revisions(User u, int limit) throws SQLException {
        try (Connection con = db.open()) {
            return dao.revisions(con, u.id(), Math.min(Math.max(limit, 1), 500));
        }
    }

    /** Wrong or unanswered official questions whose results are released (with correct answer and explanation). */
    public List<ProgressDao.MistakeRow> mistakes(User u) throws SQLException {
        requireLearner(u);
        try (Connection con = db.open()) {
            Instant now = DbTime.now(con);
            return dao.mistakes(con, u.id()).stream()
                    .filter(m -> QuizRules.resultsReleased(m.policy(), m.closesAt(), m.releasedAt(), true, now))
                    .toList();
        }
    }

    // ---------------------------------------------------------------- performance and badges

    public Performance performance(User u) throws SQLException {
        requireLearner(u);
        List<String> awarded = evaluateBadges(u);
        try (Connection con = db.open()) {
            Instant now = DbTime.now(con);
            List<ProgressDao.ScoreRow> released = dao.officialScores(con, u.id()).stream()
                    .filter(s -> QuizRules.resultsReleased(s.policy(), s.closesAt(), s.releasedAt(), true, now))
                    .toList();
            OptionalDouble avg = released.stream().mapToDouble(s -> (double) s.score() / s.maxScore()).average();
            String zone = clock.getZone().getId();
            Set<LocalDate> days = dao.activeDays(con, u.id(), zone);
            LocalDate today = now.atZone(clock.getZone()).toLocalDate();
            List<LocalDate> eventDays = dao.activity(con, u.id(), zone, 100_000).stream().map(ProgressDao.ActivityRow::day).toList();
            return new Performance(released, avg, attendance.summary(u), Streaks.current(days, today), Streaks.longest(days),
                    Streaks.weekly(eventDays, today, 8), quizAnalytics.weakTopicsForStudent(u), dao.catalogue(con, u.id()),
                    awarded, dao.activity(con, u.id(), zone, 15));
        }
    }

    /** {current streak, longest streak} for the dashboard. */
    public int[] streak(User u) throws SQLException {
        try (Connection con = db.open()) {
            Set<LocalDate> days = dao.activeDays(con, u.id(), clock.getZone().getId());
            LocalDate today = DbTime.now(con).atZone(clock.getZone()).toLocalDate();
            return new int[]{Streaks.current(days, today), Streaks.longest(days)};
        }
    }

    public List<ProgressDao.ActivityRow> recentActivity(User u, int limit) throws SQLException {
        try (Connection con = db.open()) {
            return dao.activity(con, u.id(), clock.getZone().getId(), Math.min(Math.max(limit, 1), 200));
        }
    }

    /**
     * Checks every badge rule against the student's records and awards new badges (once; the primary key makes
     * repeated evaluation harmless). Returns the codes awarded by this call.
     */
    public List<String> evaluateBadges(User u) throws SQLException {
        if (!u.role().isLearner()) {
            return List.of();
        }
        List<String> awarded = new ArrayList<>();
        try (Connection con = db.open()) {
            Instant now = DbTime.now(con);
            List<ProgressDao.ScoreRow> scores = dao.officialScores(con, u.id());
            check(con, u, "FIRST_QUIZ", !scores.isEmpty(), awarded);
            check(con, u, "PERFECT_SCORE", scores.stream().anyMatch(s -> s.score() == s.maxScore()
                    && QuizRules.resultsReleased(s.policy(), s.closesAt(), s.releasedAt(), true, now)), awarded);
            check(con, u, "FIVE_DAY_STREAK",
                    Streaks.longest(dao.activeDays(con, u.id(), clock.getZone().getId())) >= STREAK_BADGE_DAYS, awarded);
            check(con, u, "HELPFUL_CONTRIBUTOR", dao.replyUpvotesReceived(con, u.id()) >= HELPFUL_UPVOTES, awarded);
            check(con, u, "NOTES_CHAMPION", dao.approvedNotes(con, u.id()) >= CHAMPION_NOTES, awarded);
        }
        return awarded;
    }

    private void check(Connection con, User u, String code, boolean earned, List<String> awarded) throws SQLException {
        if (earned && dao.award(con, u.id(), code)) {
            awarded.add(code);
        }
    }

    private Topic topic(Connection con, long topicId) throws SQLException {
        try (var ps = con.prepareStatement("SELECT course_id FROM topics WHERE id = ?")) {
            ps.setLong(1, topicId);
            try (var rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new NotFoundException("Topic not found.");
                }
                long courseId = rs.getLong(1);
                return courses.listTopics(con, courseId).stream().filter(t -> t.id() == topicId).findFirst().orElseThrow();
            }
        }
    }

    private static void requireLearner(User u) {
        if (!u.role().isLearner()) {
            throw new AccessDeniedException("This view is for students.");
        }
    }
}
