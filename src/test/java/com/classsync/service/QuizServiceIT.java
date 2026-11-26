package com.classsync.service;

import com.classsync.Fixtures;
import com.classsync.TestDb;
import com.classsync.config.Database;
import com.classsync.model.AttemptMode;
import com.classsync.model.AttemptStatus;
import com.classsync.model.CoursePermission;
import com.classsync.model.LectureStatus;
import com.classsync.model.QuestionKind;
import com.classsync.model.QuizAttempt;
import com.classsync.model.ReviewPolicy;
import com.classsync.model.StudentQuestion;
import com.classsync.model.User;
import com.classsync.service.AppException.AccessDeniedException;
import com.classsync.service.AppException.NotFoundException;
import com.classsync.service.AppException.ValidationException;
import com.classsync.service.QuizService.AttemptResult;
import com.classsync.service.QuizService.QuestionForm;
import com.classsync.service.QuizService.QuizForm;
import com.classsync.service.QuizService.StudentState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/** Quiz editor, attempts, timing, grading, release rules and permissions against real PostgreSQL. */
class QuizServiceIT {

    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    private Database db;
    private Fixtures fx;
    private QuizService quizzes;
    private User prof;
    private User otherProf;
    private User student;
    private User student2;
    private User cr;
    private User outsider;
    private long courseId;

    @BeforeEach
    void setUp() throws SQLException {
        db = TestDb.reset();
        fx = new Fixtures(db);
        quizzes = new QuizService(db, new AccessPolicy(), Clock.system(ZONE));
        prof = fx.professor("Prof");
        otherProf = fx.professor("Other Prof");
        student = fx.student("Asha");
        student2 = fx.student("Neha");
        cr = fx.cr("Ravi");
        outsider = fx.student("Outsider");
        courseId = fx.course(prof, "CS301");
        fx.enroll(courseId, student, student2, cr);
        // even a CR holding every delegable permission must not manage quizzes
        fx.staff(courseId, cr, prof, EnumSet.allOf(CoursePermission.class));
    }

    // ------------------------------------------------------------------ helpers

    private QuizForm form(Instant opens, Instant closes, int minutes, ReviewPolicy policy, boolean practice) {
        ZonedDateTime o = opens.atZone(ZONE);
        ZonedDateTime c = closes.atZone(ZONE);
        return new QuizForm(courseId, null, "Joins quiz", "Answer all questions.", o.toLocalDate(), o.toLocalTime(),
                c.toLocalDate(), c.toLocalTime(), minutes, policy, practice);
    }

    private static QuestionForm mcq(String prompt, int marks, int correct, String... options) {
        return new QuestionForm(QuestionKind.MCQ, prompt, marks, null, "Because.", Arrays.asList(options), correct);
    }

    /** Published quiz open for the next hour with three questions worth 2, 3 and 5 marks. */
    private long openQuiz(ReviewPolicy policy, boolean practice) throws SQLException {
        Instant now = Instant.now();
        long id = quizzes.create(prof, form(now.minus(1, ChronoUnit.HOURS), now.plus(1, ChronoUnit.HOURS), 30, policy, practice));
        quizzes.addQuestion(prof, id, mcq("Which join keeps unmatched left rows?", 2, 1, "INNER", "LEFT", "CROSS"));
        quizzes.addQuestion(prof, id, new QuestionForm(QuestionKind.TRUE_FALSE, "A primary key may be NULL.", 3, null, "", null, 1));
        quizzes.addQuestion(prof, id, mcq("Which keyword removes duplicates?", 5, 0, "DISTINCT", "UNIQUE", "GROUP"));
        quizzes.publish(prof, id);
        return id;
    }

    /** Option id of the given option text for the given question position, read with the student view. */
    private long option(long attemptId, User u, int position, String text) throws SQLException {
        StudentQuestion q = quizzes.session(u, attemptId).questions().stream()
                .filter(x -> x.position() == position).findFirst().orElseThrow();
        return q.choices().stream().filter(c -> c.text().equals(text)).findFirst().orElseThrow().id();
    }

    private long questionId(long attemptId, User u, int position) throws SQLException {
        return quizzes.session(u, attemptId).questions().stream().filter(x -> x.position() == position).findFirst().orElseThrow().id();
    }

    private void answer(long attemptId, User u, int position, String text) throws SQLException {
        quizzes.saveAnswer(u, attemptId, questionId(attemptId, u, position), option(attemptId, u, position, text));
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

    /** Moves an in-progress attempt's deadline into the past, as if the student's time had run out. */
    private void expire(long attemptId) throws SQLException {
        sql("UPDATE quiz_attempts SET started_at = now() - interval '1 hour', deadline_at = now() - interval '1 second' WHERE id = ?", attemptId);
    }

    // ------------------------------------------------------------------ editor validation

    @Test
    void questionValidationRules() throws SQLException {
        Instant now = Instant.now();
        long id = quizzes.create(prof, form(now.plus(1, ChronoUnit.DAYS), now.plus(2, ChronoUnit.DAYS), 20, ReviewPolicy.AFTER_CLOSE, true));
        assertThrows(ValidationException.class, () -> quizzes.addQuestion(prof, id, mcq("Only one option?", 1, 0, "A")), "needs two options");
        assertThrows(ValidationException.class, () -> quizzes.addQuestion(prof, id, mcq("Duplicates?", 1, 0, "Yes", "yes")), "duplicate text");
        assertThrows(ValidationException.class, () -> quizzes.addQuestion(prof, id, mcq("No answer?", 1, 5, "A", "B")), "correct index out of range");
        assertThrows(ValidationException.class, () -> quizzes.addQuestion(prof, id, mcq("Zero marks?", 0, 0, "A", "B")), "marks must be positive");
        assertThrows(ValidationException.class, () -> quizzes.addQuestion(prof, id, mcq("Blank correct?", 1, 1, "A", " ", "C")),
                "the correct option cannot be blank");

        // blank fields are skipped and the correct index follows its option
        quizzes.addQuestion(prof, id, mcq("Pick C", 1, 2, "A", "", "C"));
        quizzes.addQuestion(prof, id, new QuestionForm(QuestionKind.TRUE_FALSE, "Sky is blue", 1, null, "", null, 0));
        var editor = quizzes.editor(prof, id);
        assertEquals(2, editor.questions().size());
        assertEquals("C", editor.questions().get(0).correctOption().text());
        assertEquals(List.of("True", "False"), editor.questions().get(1).options().stream().map(o -> o.text()).toList());
        assertEquals(2, editor.quiz().totalMarks(), "total marks are derived from the questions");
    }

    @Test
    void databaseAllowsAtMostOneCorrectOption() throws SQLException {
        long id = openQuiz(ReviewPolicy.AFTER_SUBMIT, false);
        long questionId = quizzes.editor(prof, id).questions().get(0).id();
        SQLException e = assertThrows(SQLException.class,
                () -> sql("UPDATE question_options SET is_correct = TRUE WHERE question_id = ?", questionId));
        assertEquals("23505", e.getSQLState(), "partial unique index question_options_one_correct");
    }

    @Test
    void publishRequiresQuestionsAndAVisibleLecture() throws SQLException {
        Instant now = Instant.now();
        long empty = quizzes.create(prof, form(now, now.plus(1, ChronoUnit.HOURS), 10, ReviewPolicy.AFTER_CLOSE, false));
        assertThrows(ValidationException.class, () -> quizzes.publish(prof, empty));

        long draftLecture = fx.lecture(courseId, prof, 1, 24, LectureStatus.DRAFT);
        ZonedDateTime o = now.atZone(ZONE);
        ZonedDateTime c = now.plus(1, ChronoUnit.HOURS).atZone(ZONE);
        long attached = quizzes.create(prof, new QuizForm(courseId, draftLecture, "Lecture quiz", "", o.toLocalDate(),
                o.toLocalTime(), c.toLocalDate(), c.toLocalTime(), 10, ReviewPolicy.AFTER_CLOSE, false));
        quizzes.addQuestion(prof, attached, mcq("Pick A", 1, 0, "A", "B"));
        assertThrows(ValidationException.class, () -> quizzes.publish(prof, attached), "draft lecture");
        sql("UPDATE lectures SET status = 'PUBLISHED' WHERE id = ?", draftLecture);
        quizzes.publish(prof, attached);
    }

    // ------------------------------------------------------------------ answer-key isolation

    @Test
    void studentModelsNeverCarryTheAnswerKey() throws SQLException {
        for (RecordComponent rc : StudentQuestion.class.getRecordComponents()) {
            assertFalse(rc.getName().toLowerCase().contains("correct") || rc.getName().toLowerCase().contains("explanation"),
                    "StudentQuestion must not expose " + rc.getName());
        }
        for (RecordComponent rc : StudentQuestion.Choice.class.getRecordComponents()) {
            assertFalse(rc.getName().toLowerCase().contains("correct"), "Choice must not expose " + rc.getName());
        }

        long quiz = openQuiz(ReviewPolicy.AFTER_CLOSE, false);
        assertThrows(AccessDeniedException.class, () -> quizzes.editor(student, quiz), "students cannot open the editor");
        long attempt = quizzes.start(student, quiz, AttemptMode.OFFICIAL);
        answer(attempt, student, 1, "LEFT");
        AttemptResult result = quizzes.submit(student, attempt);
        assertFalse(result.released());
        assertNull(result.attempt().score(), "score hidden until the quiz closes");
        assertThrows(AccessDeniedException.class, () -> quizzes.review(student, attempt));
        assertNull(quizzes.forStudent(student, quiz).official().score());
        assertEquals(StudentState.AWAITING_RESULTS, quizzes.forStudent(student, quiz).state());
    }

    @Test
    void manualReleaseOpensReview() throws SQLException {
        long quiz = openQuiz(ReviewPolicy.MANUAL, false);
        long attempt = quizzes.start(student, quiz, AttemptMode.OFFICIAL);
        answer(attempt, student, 3, "DISTINCT");
        quizzes.submit(student, attempt);
        assertThrows(AccessDeniedException.class, () -> quizzes.review(student, attempt));
        assertThrows(AccessDeniedException.class, () -> quizzes.releaseResults(cr, quiz), "CR cannot release marks");

        quizzes.releaseResults(prof, quiz);
        var review = quizzes.review(student, attempt);
        assertEquals(3, review.items().size());
        assertTrue(review.items().get(2).correct());
        assertNull(review.items().get(0).chosenOptionId(), "unanswered question is shown as unanswered");
        assertEquals(5, review.attempt().score());
        assertEquals(StudentState.RESULT_RELEASED, quizzes.forStudent(student, quiz).state());
    }

    // ------------------------------------------------------------------ grading and timing

    @Test
    void gradingAddsMarksOfCorrectAnswersOnly() throws SQLException {
        long quiz = openQuiz(ReviewPolicy.AFTER_SUBMIT, false);
        long attempt = quizzes.start(student, quiz, AttemptMode.OFFICIAL);
        answer(attempt, student, 1, "LEFT");       // correct, 2 marks
        answer(attempt, student, 2, "True");       // wrong, 3 marks
        answer(attempt, student, 3, "DISTINCT");   // correct, 5 marks
        AttemptResult r = quizzes.submit(student, attempt);
        assertTrue(r.released());
        assertEquals(AttemptStatus.SUBMITTED, r.attempt().status());
        assertEquals(7, r.attempt().score());
        assertEquals(10, r.attempt().maxScore());
    }

    @Test
    void deadlineIsTheEarlierOfDurationAndClosingTime() throws SQLException {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        // closes in 10 minutes, 30-minute duration -> deadline is the closing time
        long closing = quizzes.create(prof, form(now.minus(1, ChronoUnit.HOURS), now.plus(10, ChronoUnit.MINUTES), 30, ReviewPolicy.AFTER_CLOSE, false));
        quizzes.addQuestion(prof, closing, mcq("Pick A", 1, 0, "A", "B"));
        quizzes.publish(prof, closing);
        QuizAttempt a = quizzes.session(student, quizzes.start(student, closing, AttemptMode.OFFICIAL)).attempt();
        assertEquals(now.plus(10, ChronoUnit.MINUTES), a.deadlineAt());

        // closes in 3 hours, 15-minute duration -> deadline is start + 15 minutes
        long longWindow = quizzes.create(prof, form(now.minus(1, ChronoUnit.HOURS), now.plus(3, ChronoUnit.HOURS), 15, ReviewPolicy.AFTER_CLOSE, false));
        quizzes.addQuestion(prof, longWindow, mcq("Pick A", 1, 0, "A", "B"));
        quizzes.publish(prof, longWindow);
        var session = quizzes.session(student, quizzes.start(student, longWindow, AttemptMode.OFFICIAL));
        assertEquals(Duration.ofMinutes(15), Duration.between(session.attempt().startedAt(), session.attempt().deadlineAt()));
        assertTrue(session.secondsLeft() > 14 * 60 && session.secondsLeft() <= 15 * 60);
    }

    @Test
    void quizCanOnlyBeStartedWhileOpen() throws SQLException {
        long quiz = openQuiz(ReviewPolicy.AFTER_CLOSE, false);
        sql("UPDATE quizzes SET opens_at = now() + interval '1 hour', closes_at = now() + interval '2 hours' WHERE id = ?", quiz);
        assertThrows(ValidationException.class, () -> quizzes.start(student, quiz, AttemptMode.OFFICIAL), "not open yet");
        assertEquals(StudentState.UPCOMING, quizzes.forStudent(student, quiz).state());

        sql("UPDATE quizzes SET opens_at = now() - interval '2 hours', closes_at = now() - interval '1 second' WHERE id = ?", quiz);
        assertThrows(ValidationException.class, () -> quizzes.start(student, quiz, AttemptMode.OFFICIAL), "closed (boundary)");
        assertEquals(StudentState.MISSED, quizzes.forStudent(student, quiz).state());
    }

    @Test
    void savedAnswersSurviveARestartAndCanBeChanged() throws SQLException {
        long quiz = openQuiz(ReviewPolicy.AFTER_SUBMIT, false);
        long attempt = quizzes.start(student, quiz, AttemptMode.OFFICIAL);
        answer(attempt, student, 1, "INNER");
        answer(attempt, student, 1, "LEFT");   // changed mind
        answer(attempt, student, 2, "False");

        // "restart": a brand-new service instance resumes the same attempt from the database
        QuizService afterRestart = new QuizService(db, new AccessPolicy(), Clock.system(ZONE));
        assertEquals(attempt, afterRestart.start(student, quiz, AttemptMode.OFFICIAL), "start resumes the open attempt");
        var session = afterRestart.session(student, attempt);
        assertEquals(2, session.saved().size());
        assertEquals(option(attempt, student, 1, "LEFT"), session.saved().get(questionId(attempt, student, 1)));
        assertTrue(session.secondsLeft() > 0);
    }

    @Test
    void expiredAttemptIsAutoSubmittedWithSavedAnswers() throws SQLException {
        long quiz = openQuiz(ReviewPolicy.AFTER_SUBMIT, false);
        long attempt = quizzes.start(student, quiz, AttemptMode.OFFICIAL);
        answer(attempt, student, 3, "DISTINCT");
        long q1 = questionId(attempt, student, 1);
        long left = option(attempt, student, 1, "LEFT");
        expire(attempt);

        ValidationException late = assertThrows(ValidationException.class, () -> quizzes.saveAnswer(student, attempt, q1, left));
        assertTrue(late.getMessage().contains("Time is up"));
        var session = quizzes.session(student, attempt);
        assertEquals(AttemptStatus.EXPIRED, session.attempt().status());
        assertEquals(5, session.attempt().score(), "only the answer saved before the deadline counts");
        assertTrue(session.questions().isEmpty(), "a finished attempt shows no editable questions");
        assertThrows(ValidationException.class, () -> quizzes.saveAnswer(student, attempt, q1, left), "no edits after submission");
    }

    @Test
    void expiredAttemptsAreFinalisedWhenTheStudentOpensTheQuizList() throws SQLException {
        long quiz = openQuiz(ReviewPolicy.AFTER_SUBMIT, false);
        long attempt = quizzes.start(student, quiz, AttemptMode.OFFICIAL);
        expire(attempt);
        var view = quizzes.forStudent(student, null, null).get(0);
        assertEquals(StudentState.RESULT_RELEASED, view.state());
        assertEquals(AttemptStatus.EXPIRED, view.official().status());
        assertEquals(0, view.official().score());
    }

    @Test
    void submitIsIdempotent() throws SQLException {
        long quiz = openQuiz(ReviewPolicy.AFTER_SUBMIT, false);
        long attempt = quizzes.start(student, quiz, AttemptMode.OFFICIAL);
        answer(attempt, student, 1, "LEFT");
        AttemptResult first = quizzes.submit(student, attempt);
        AttemptResult second = quizzes.submit(student, attempt);
        assertEquals(first.attempt().score(), second.attempt().score());
        assertEquals(first.attempt().submittedAt(), second.attempt().submittedAt());
        assertEquals(1, count("SELECT count(*) FROM audit_logs WHERE action = 'QUIZ_SUBMIT' AND entity_id = ?", attempt));
        assertThrows(ValidationException.class, () -> quizzes.start(student, quiz, AttemptMode.OFFICIAL), "one official attempt");
    }

    @Test
    void concurrentSubmitsScoreExactlyOnce() throws Exception {
        long quiz = openQuiz(ReviewPolicy.AFTER_SUBMIT, false);
        long attempt = quizzes.start(student, quiz, AttemptMode.OFFICIAL);
        answer(attempt, student, 1, "LEFT");
        List<AttemptResult> results = race(8, () -> quizzes.submit(student, attempt));
        assertEquals(1, results.stream().map(r -> r.attempt().submittedAt()).distinct().count());
        assertTrue(results.stream().allMatch(r -> r.attempt().score() == 2));
        assertEquals(1, count("SELECT count(*) FROM audit_logs WHERE action = 'QUIZ_SUBMIT' AND entity_id = ?", attempt));
    }

    @Test
    void concurrentStartsCreateOneOfficialAttempt() throws Exception {
        long quiz = openQuiz(ReviewPolicy.AFTER_SUBMIT, false);
        List<Object> outcomes = race(6, () -> {
            try {
                return quizzes.start(student, quiz, AttemptMode.OFFICIAL);
            } catch (ValidationException e) {
                return e;   // the loser of a race is told to refresh — never a second attempt
            }
        });
        assertEquals(1, count("SELECT count(*) FROM quiz_attempts WHERE quiz_id = ? AND student_id = ?", quiz, student.id()));
        assertTrue(outcomes.stream().anyMatch(o -> o instanceof Long));
    }

    private static <T> List<T> race(int threads, Callable<T> work) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return work.call();
                }));
            }
            go.countDown();
            List<T> out = new ArrayList<>();
            for (Future<T> f : futures) {
                out.add(f.get());
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ practice vs official

    @Test
    void practiceAttemptsAreSeparateFromOfficialResults() throws SQLException {
        long quiz = openQuiz(ReviewPolicy.AFTER_SUBMIT, true);
        assertThrows(ValidationException.class, () -> quizzes.start(student, quiz, AttemptMode.PRACTICE),
                "no practice before the official attempt");
        long official = quizzes.start(student, quiz, AttemptMode.OFFICIAL);
        answer(official, student, 1, "LEFT");
        quizzes.submit(student, official);

        long practice = quizzes.start(student, quiz, AttemptMode.PRACTICE);
        answer(practice, student, 1, "LEFT");
        answer(practice, student, 2, "False");
        answer(practice, student, 3, "DISTINCT");
        AttemptResult p = quizzes.submit(student, practice);
        assertEquals(10, p.attempt().score());
        assertEquals(2, p.attempt().attemptNo());

        var view = quizzes.forStudent(student, quiz);
        assertEquals(2, view.official().score(), "official result unchanged by practice");
        assertEquals(1, view.practice().size());

        var analytics = new QuizAnalyticsService(db, new AccessPolicy(), quizzes).analytics(prof, quiz);
        assertEquals(1, analytics.finished(), "practice attempts are excluded from analytics");
        assertEquals(2.0, analytics.averageScore());
    }

    @Test
    void practiceIsRefusedWhenNotAllowedOrNotReleased() throws SQLException {
        long closedReview = openQuiz(ReviewPolicy.AFTER_CLOSE, true);
        long a = quizzes.start(student, closedReview, AttemptMode.OFFICIAL);
        quizzes.submit(student, a);
        assertThrows(ValidationException.class, () -> quizzes.start(student, closedReview, AttemptMode.PRACTICE),
                "results not released until the quiz closes");

        long noPractice = openQuiz(ReviewPolicy.AFTER_SUBMIT, false);
        long b = quizzes.start(student, noPractice, AttemptMode.OFFICIAL);
        quizzes.submit(student, b);
        assertThrows(ValidationException.class, () -> quizzes.start(student, noPractice, AttemptMode.PRACTICE));
    }

    // ------------------------------------------------------------------ freezing and permissions

    @Test
    void questionsAndSettingsFreezeOnceAnAttemptStarts() throws SQLException {
        long quiz = openQuiz(ReviewPolicy.AFTER_CLOSE, false);
        quizzes.start(student, quiz, AttemptMode.OFFICIAL);
        long questionId = quizzes.editor(prof, quiz).questions().get(0).id();
        assertTrue(quizzes.editor(prof, quiz).locked());
        assertThrows(ValidationException.class, () -> quizzes.addQuestion(prof, quiz, mcq("Late?", 1, 0, "A", "B")));
        assertThrows(ValidationException.class, () -> quizzes.updateQuestion(prof, questionId, mcq("Changed", 1, 0, "A", "B")));
        assertThrows(ValidationException.class, () -> quizzes.deleteQuestion(prof, questionId));
        assertThrows(ValidationException.class, () -> quizzes.unpublish(prof, quiz));
    }

    @Test
    void onlyTheCourseProfessorManagesQuizzes() throws SQLException {
        Instant now = Instant.now();
        QuizForm f = form(now, now.plus(1, ChronoUnit.HOURS), 10, ReviewPolicy.AFTER_CLOSE, false);
        assertThrows(AccessDeniedException.class, () -> quizzes.create(student, f));
        assertThrows(AccessDeniedException.class, () -> quizzes.create(cr, f), "CR delegation never covers quizzes");
        assertThrows(AccessDeniedException.class, () -> quizzes.create(otherProf, f));
        long quiz = openQuiz(ReviewPolicy.AFTER_CLOSE, false);
        assertThrows(AccessDeniedException.class, () -> quizzes.addQuestion(cr, quiz, mcq("Pick A", 1, 0, "A", "B")));
        var analytics = new QuizAnalyticsService(db, new AccessPolicy(), quizzes);
        assertThrows(AccessDeniedException.class, () -> analytics.analytics(cr, quiz));
        assertThrows(AccessDeniedException.class, () -> analytics.analytics(otherProf, quiz));
    }

    @Test
    void attemptsAreLimitedToEnrolledLearnersAndTheirOwnAttempts() throws SQLException {
        long quiz = openQuiz(ReviewPolicy.AFTER_SUBMIT, false);
        assertThrows(NotFoundException.class, () -> quizzes.start(outsider, quiz, AttemptMode.OFFICIAL));
        assertThrows(AccessDeniedException.class, () -> quizzes.start(prof, quiz, AttemptMode.OFFICIAL));
        long attempt = quizzes.start(student, quiz, AttemptMode.OFFICIAL);
        assertThrows(NotFoundException.class, () -> quizzes.session(student2, attempt));
        assertThrows(NotFoundException.class, () -> quizzes.submit(student2, attempt));

        Instant now = Instant.now();
        long draft = quizzes.create(prof, form(now, now.plus(1, ChronoUnit.HOURS), 10, ReviewPolicy.AFTER_CLOSE, false));
        assertThrows(NotFoundException.class, () -> quizzes.forStudent(student, draft), "drafts are invisible to students");
        assertTrue(quizzes.forStudent(student, null, null).stream().noneMatch(s -> s.quiz().id() == draft));
    }

    @Test
    void answerMustBelongToTheAttemptsQuiz() throws SQLException {
        long quizA = openQuiz(ReviewPolicy.AFTER_SUBMIT, false);
        long quizB = openQuiz(ReviewPolicy.AFTER_SUBMIT, false);
        long a = quizzes.start(student, quizA, AttemptMode.OFFICIAL);
        long b = quizzes.start(student2, quizB, AttemptMode.OFFICIAL);
        long foreignQuestion = questionId(b, student2, 1);
        long foreignOption = option(b, student2, 1, "LEFT");
        assertThrows(ValidationException.class, () -> quizzes.saveAnswer(student, a, foreignQuestion, foreignOption));
        long ownQuestion = questionId(a, student, 1);
        assertThrows(ValidationException.class, () -> quizzes.saveAnswer(student, a, ownQuestion, foreignOption),
                "option of another question");
    }

    /** The desktop app connects as the restricted classsync_app role, not the schema owner used by fixtures. */
    @Test
    void wholeQuizFlowWorksWithTheRestrictedApplicationRole() throws SQLException {
        var cfg = TestDb.config();
        String appPassword = cfg.get(com.classsync.config.AppConfig.APP_PASSWORD);
        org.junit.jupiter.api.Assumptions.assumeTrue(appPassword != null, "app password not configured");
        Database appDb = new Database(cfg.require(com.classsync.config.AppConfig.TEST_DB_URL),
                cfg.require(com.classsync.config.AppConfig.APP_USER), appPassword);
        QuizService asApp = new QuizService(appDb, new AccessPolicy(), Clock.system(ZONE));
        Instant now = Instant.now();
        long quiz = asApp.create(prof, form(now.minus(1, ChronoUnit.HOURS), now.plus(1, ChronoUnit.HOURS), 10,
                ReviewPolicy.MANUAL, true));
        asApp.addQuestion(prof, quiz, mcq("Pick B", 2, 1, "A", "B"));
        asApp.publish(prof, quiz);
        long attempt = asApp.start(student, quiz, AttemptMode.OFFICIAL);
        var q = asApp.session(student, attempt).questions().get(0);
        asApp.saveAnswer(student, attempt, q.id(), q.choices().get(1).id());
        asApp.submit(student, attempt);
        asApp.releaseResults(prof, quiz);
        assertEquals(2, asApp.review(student, attempt).attempt().score());
        long practice = asApp.start(student, quiz, AttemptMode.PRACTICE);
        expire(practice);
        assertEquals(AttemptStatus.EXPIRED, asApp.result(student, practice).attempt().status());
        var analytics = new QuizAnalyticsService(appDb, new AccessPolicy(), asApp).analytics(prof, quiz);
        assertEquals(1, analytics.finished());
    }
}
