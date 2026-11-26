package com.classsync.service;

import com.classsync.Fixtures;
import com.classsync.TestDb;
import com.classsync.config.Database;
import com.classsync.dao.CourseDao;
import com.classsync.model.AttemptMode;
import com.classsync.model.QuestionKind;
import com.classsync.model.ReviewPolicy;
import com.classsync.model.StudentQuestion;
import com.classsync.model.User;
import com.classsync.service.QuizRules.Verdict;
import com.classsync.service.QuizService.QuestionForm;
import com.classsync.service.QuizService.QuizForm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Professor analytics and the Weak Topic Detector on real data. */
class QuizAnalyticsServiceIT {

    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    private QuizService quizzes;
    private QuizAnalyticsService analytics;
    private User prof;
    private User asha;
    private User neha;
    private User kabir;
    private long courseId;
    private long joins;
    private long sqlBasics;

    @BeforeEach
    void setUp() throws SQLException {
        Database db = TestDb.reset();
        Fixtures fx = new Fixtures(db);
        AccessPolicy access = new AccessPolicy();
        quizzes = new QuizService(db, access, Clock.system(ZONE));
        analytics = new QuizAnalyticsService(db, access, quizzes);
        prof = fx.professor("Prof");
        asha = fx.student("Asha");
        neha = fx.student("Neha");
        kabir = fx.student("Kabir");
        courseId = fx.course(prof, "CS301");
        fx.enroll(courseId, asha, neha, kabir);
        try (Connection con = db.open()) {
            CourseDao courses = new CourseDao();
            joins = courses.insertTopic(con, courseId, 2, "Joins", 1);
            sqlBasics = courses.insertTopic(con, courseId, 1, "SQL basics", 2);
        }
    }

    /**
     * Quiz with 3 "Joins" questions (1 mark each, correct = "Right") and 2 "SQL basics" questions.
     */
    private long quiz(ReviewPolicy policy) throws SQLException {
        Instant now = Instant.now();
        ZonedDateTime o = now.minus(1, ChronoUnit.HOURS).atZone(ZONE);
        ZonedDateTime c = now.plus(1, ChronoUnit.HOURS).atZone(ZONE);
        long id = quizzes.create(prof, new QuizForm(courseId, null, "Unit 2 quiz", "", o.toLocalDate(), o.toLocalTime(),
                c.toLocalDate(), c.toLocalTime(), 20, policy, false));
        for (int i = 1; i <= 3; i++) {
            quizzes.addQuestion(prof, id, new QuestionForm(QuestionKind.MCQ, "Joins question " + i, 1, joins, "",
                    List.of("Right", "Wrong", "Other"), 0));
        }
        for (int i = 1; i <= 2; i++) {
            quizzes.addQuestion(prof, id, new QuestionForm(QuestionKind.MCQ, "SQL question " + i, 1, sqlBasics, "",
                    List.of("Right", "Wrong"), 0));
        }
        quizzes.publish(prof, id);
        return id;
    }

    /** Student answers: one letter per question, R = right, W = wrong, - = skip. */
    private void attempt(User u, long quizId, String pattern) throws SQLException {
        long a = quizzes.start(u, quizId, AttemptMode.OFFICIAL);
        List<StudentQuestion> qs = quizzes.session(u, a).questions();
        for (int i = 0; i < pattern.length(); i++) {
            char ch = pattern.charAt(i);
            if (ch == '-') {
                continue;
            }
            String text = ch == 'R' ? "Right" : "Wrong";
            long option = qs.get(i).choices().stream().filter(c -> c.text().equals(text)).findFirst().orElseThrow().id();
            quizzes.saveAnswer(u, a, qs.get(i).id(), option);
        }
        quizzes.submit(u, a);
    }

    @Test
    void analyticsSummariseOfficialAttempts() throws SQLException {
        long q = quiz(ReviewPolicy.AFTER_SUBMIT);
        var empty = analytics.analytics(prof, q);
        assertNull(empty.averageScore(), "no attempts → no average, not 0");
        assertEquals(0.0, empty.participation().orElseThrow());
        assertTrue(empty.questions().get(0).accuracy().isEmpty(), "no finished attempts → no accuracy");

        attempt(asha, q, "RRRRR");   // 5
        attempt(neha, q, "RWWRR");   // 3, wrong answer "Wrong" on Q2 and Q3
        var a = analytics.analytics(prof, q);
        assertEquals(3, a.enrolled());
        assertEquals(2, a.finished());
        assertEquals(2.0 / 3, a.participation().orElseThrow(), 1e-9);
        assertEquals(4.0, a.averageScore());
        assertEquals(5, a.highest());
        assertEquals(3, a.lowest());
        var q2 = a.questions().get(1);
        assertEquals(0.5, q2.accuracy().orElseThrow(), 1e-9);
        assertEquals("Wrong", q2.commonWrong());
        assertEquals(1, q2.commonWrongCount());
        assertEquals("Joins", q2.topic());
        assertEquals(2, a.submissions().size());
        assertEquals("Asha", a.submissions().get(0).studentName(), "best score first");
    }

    @Test
    void weakTopicsUseThresholdsAndMinimumSample() throws SQLException {
        long q = quiz(ReviewPolicy.AFTER_SUBMIT);
        attempt(asha, q, "RWWRR");   // Joins 1/3 = 33% (weak), SQL basics 2/2 (only 2 seen → insufficient)
        List<QuizAnalyticsService.TopicInsight> mine = analytics.weakTopicsForStudent(asha);
        assertEquals(2, mine.size());
        assertEquals("Joins", mine.get(0).topic());
        assertEquals(Verdict.WEAK, mine.get(0).verdict());
        assertEquals(3, mine.get(0).seen());
        assertEquals(Verdict.INSUFFICIENT_DATA, mine.get(1).verdict());

        assertTrue(analytics.weakTopicsForStudent(kabir).isEmpty(), "no attempts → no verdicts at all");
        // class level: only 3 + 2 answers so far, below the class minimum of 10
        assertTrue(analytics.weakTopicsForCourse(prof, courseId).stream().allMatch(t -> t.verdict() == Verdict.INSUFFICIENT_DATA));
    }

    @Test
    void unreleasedResultsNeverFeedTheStudentsWeakTopics() throws SQLException {
        long q = quiz(ReviewPolicy.MANUAL);
        attempt(asha, q, "WWWWW");
        assertTrue(analytics.weakTopicsForStudent(asha).isEmpty(), "correctness stays hidden before release");
        quizzes.releaseResults(prof, q);
        assertEquals(Verdict.WEAK, analytics.weakTopicsForStudent(asha).get(0).verdict());
    }

    @Test
    void unansweredQuestionsCountAsSeenAndIncorrect() throws SQLException {
        long q = quiz(ReviewPolicy.AFTER_SUBMIT);
        attempt(asha, q, "R--RR");
        var joinsInsight = analytics.weakTopicsForStudent(asha).stream().filter(t -> t.topic().equals("Joins")).findFirst().orElseThrow();
        assertEquals(3, joinsInsight.seen());
        assertEquals(1, joinsInsight.correct());
    }
}
