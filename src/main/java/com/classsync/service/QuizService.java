package com.classsync.service;

import com.classsync.config.Database;
import com.classsync.config.Tx;
import com.classsync.dao.AttemptDao;
import com.classsync.dao.AuditDao;
import com.classsync.dao.CourseDao;
import com.classsync.dao.LectureDao;
import com.classsync.dao.NotificationDao;
import com.classsync.dao.QuizDao;
import com.classsync.model.AttemptMode;
import com.classsync.model.AttemptStatus;
import com.classsync.model.Lecture;
import com.classsync.model.LectureStatus;
import com.classsync.model.Question;
import com.classsync.model.QuestionKind;
import com.classsync.model.Quiz;
import com.classsync.model.QuizAttempt;
import com.classsync.model.QuizStatus;
import com.classsync.model.ReviewPolicy;
import com.classsync.model.StudentQuestion;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Lecture quizzes: the professor's editor, student attempts and result review.
 *
 * Integrity rules (see docs/VIVA_GUIDE.md, Module 5):
 *   - deadline = least(start + duration, closes_at), computed by PostgreSQL, checked on every save and submit;
 *   - answers are saved one at a time (autosave), so a crash or restart loses nothing;
 *   - submit is idempotent: a locked, conditional UPDATE finishes an attempt exactly once;
 *   - expired attempts are submitted automatically with their saved answers whenever they are next touched;
 *   - questions are frozen once any attempt exists;
 *   - answer keys reach students only through review(), and only after release.
 */
public class QuizService {

    /** Quiz settings form. Dates/times are in the academic time zone. */
    public record QuizForm(long courseId, Long lectureId, String title, String instructions,
                           LocalDate openDate, LocalTime openTime, LocalDate closeDate, LocalTime closeTime,
                           int durationMinutes, ReviewPolicy reviewPolicy, boolean allowPractice) {
    }

    /** Question form. For TRUE_FALSE the options are ignored and correctIndex 0 = True, 1 = False. */
    public record QuestionForm(QuestionKind kind, String prompt, int marks, Long topicId, String explanation,
                               List<String> options, int correctIndex) {
    }

    /** Editor view (course professor only). locked = attempts exist, so questions and settings are frozen. */
    public record Editor(Quiz quiz, List<Question> questions, boolean locked) {
    }

    /** Where a quiz stands for one student. */
    public enum StudentState {
        UPCOMING("Opens soon"), OPEN("Open now"), IN_PROGRESS("In progress"),
        AWAITING_RESULTS("Submitted — results pending"), RESULT_RELEASED("Result available"), MISSED("Missed");

        private final String label;

        StudentState(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * A quiz from one student's point of view. official/openAttempt have their score hidden (null)
     * until results are released.
     */
    public record StudentQuiz(Quiz quiz, StudentState state, QuizAttempt official, QuizAttempt openAttempt,
                              boolean released, boolean practiceAllowed, List<QuizAttempt> practice) {
    }

    /** Everything the attempt screen needs. questions is empty once the attempt is finished. */
    public record AttemptSession(Quiz quiz, QuizAttempt attempt, List<StudentQuestion> questions,
                                 Map<Long, Long> saved, long secondsLeft) {
    }

    /** Outcome after submitting; the score is null unless results are released. */
    public record AttemptResult(Quiz quiz, QuizAttempt attempt, boolean released) {
    }

    public record ReviewItem(Question question, Long chosenOptionId, boolean correct) {
    }

    public record AttemptReview(Quiz quiz, QuizAttempt attempt, List<ReviewItem> items) {
    }

    private final Database db;
    private final AccessPolicy access;
    private final Clock clock;
    private final QuizDao quizzes = new QuizDao();
    private final AttemptDao attempts = new AttemptDao();
    private final LectureDao lectures = new LectureDao();
    private final CourseDao courses = new CourseDao();
    private final AuditDao audit = new AuditDao();
    private final NotificationDao notifications = new NotificationDao();

    public QuizService(Database db, AccessPolicy access, Clock clock) {
        this.db = db;
        this.access = access;
        this.clock = clock;
    }

    public ZoneId zone() {
        return clock.getZone();
    }

    // =================================================================== editor (course professor)

    public long create(User u, QuizForm f) throws SQLException {
        return Tx.inTransaction(db, con -> {
            access.requireCourseProfessor(con, u, f.courseId());
            requireActiveCourse(con, f.courseId());
            QuizDao.QuizData data = validate(con, f);
            long id = quizzes.insert(con, data, u.id());
            audit.log(con, u.id(), "QUIZ_CREATE", "quiz", id, data.title());
            return id;
        });
    }

    public void update(User u, long quizId, QuizForm f) throws SQLException {
        Tx.inTransaction(db, con -> {
            Quiz q = editable(con, u, quizId);
            if (q.courseId() != f.courseId()) {
                throw new ValidationException("A quiz cannot be moved to another course.");
            }
            quizzes.update(con, quizId, validate(con, f));
            audit.log(con, u.id(), "QUIZ_UPDATE", "quiz", quizId, "");
            return null;
        });
    }

    public long addQuestion(User u, long quizId, QuestionForm f) throws SQLException {
        return Tx.inTransaction(db, con -> {
            Quiz q = editable(con, u, quizId);
            long id = quizzes.insertQuestion(con, quizId, validate(con, q.courseId(), f));
            audit.log(con, u.id(), "QUESTION_ADD", "quiz", quizId, "question " + id);
            return id;
        });
    }

    public void updateQuestion(User u, long questionId, QuestionForm f) throws SQLException {
        Tx.inTransaction(db, con -> {
            long quizId = quizzes.quizOfQuestion(con, questionId).orElseThrow(() -> new NotFoundException("Question not found."));
            Quiz q = editable(con, u, quizId);
            quizzes.updateQuestion(con, questionId, validate(con, q.courseId(), f));
            audit.log(con, u.id(), "QUESTION_UPDATE", "quiz", quizId, "question " + questionId);
            return null;
        });
    }

    public void deleteQuestion(User u, long questionId) throws SQLException {
        Tx.inTransaction(db, con -> {
            long quizId = quizzes.quizOfQuestion(con, questionId).orElseThrow(() -> new NotFoundException("Question not found."));
            Quiz q = editable(con, u, quizId);
            if (q.status() == QuizStatus.PUBLISHED && q.questionCount() <= 1) {
                throw new ValidationException("A published quiz needs at least one question. Unpublish it first.");
            }
            quizzes.deleteQuestion(con, questionId);
            audit.log(con, u.id(), "QUESTION_DELETE", "quiz", quizId, "question " + questionId);
            return null;
        });
    }

    /** Publishes after checking the quiz is complete and still in the future. */
    public void publish(User u, long quizId) throws SQLException {
        Tx.inTransaction(db, con -> {
            Quiz q = lockOwned(con, u, quizId);
            if (q.status() == QuizStatus.PUBLISHED) {
                return null;
            }
            requireActiveCourse(con, q.courseId());
            List<Question> questions = quizzes.questions(con, quizId);
            if (questions.isEmpty()) {
                throw new ValidationException("Add at least one question before publishing.");
            }
            for (Question qu : questions) {
                if (qu.options().size() < 2 || qu.options().stream().filter(o -> o.correct()).count() != 1) {
                    throw new ValidationException("Question " + qu.position() + " needs at least two options and exactly one correct answer.");
                }
            }
            if (!q.closesAt().isAfter(attempts.dbNow(con))) {
                throw new ValidationException("The closing time has already passed. Change the schedule first.");
            }
            if (q.lectureId() != null) {
                Lecture l = lectures.findById(con, q.lectureId()).orElseThrow();
                if (l.status() == LectureStatus.DRAFT) {
                    throw new ValidationException("Publish lecture " + l.lectureNo() + " first; students cannot see draft lectures.");
                }
                if (l.status() == LectureStatus.CANCELLED) {
                    throw new ValidationException("Lecture " + l.lectureNo() + " is cancelled.");
                }
            }
            quizzes.setStatus(con, quizId, QuizStatus.PUBLISHED);
            audit.log(con, u.id(), "QUIZ_PUBLISH", "quiz", quizId, questions.size() + " questions");
            notifications.notifyCourseLearners(con, q.courseId(), u.id(), new NotificationDao.Message("QUIZ",
                    "New quiz in " + q.courseCode() + ": " + q.title(),
                    q.durationMinutes() + " minutes · open until " + q.closesAt().atZone(zone()).toLocalDate(),
                    "QUIZ", quizId, "quiz-published:" + quizId));
            return null;
        });
    }

    public void unpublish(User u, long quizId) throws SQLException {
        Tx.inTransaction(db, con -> {
            editable(con, u, quizId);
            quizzes.setStatus(con, quizId, QuizStatus.DRAFT);
            audit.log(con, u.id(), "QUIZ_UNPUBLISH", "quiz", quizId, "");
            return null;
        });
    }

    /** For the MANUAL review policy: makes scores and answers visible to students. */
    public void releaseResults(User u, long quizId) throws SQLException {
        Tx.inTransaction(db, con -> {
            Quiz q = lockOwned(con, u, quizId);
            if (q.status() != QuizStatus.PUBLISHED) {
                throw new ValidationException("Only a published quiz has results to release.");
            }
            if (quizzes.releaseResults(con, quizId)) {
                audit.log(con, u.id(), "QUIZ_RELEASE", "quiz", quizId, "");
            }
            return null;
        });
    }

    public void deleteDraft(User u, long quizId) throws SQLException {
        Tx.inTransaction(db, con -> {
            Quiz q = editable(con, u, quizId);
            if (q.status() != QuizStatus.DRAFT) {
                throw new ValidationException("Unpublish the quiz before deleting it.");
            }
            quizzes.delete(con, quizId);
            audit.log(con, u.id(), "QUIZ_DELETE", "quiz", quizId, q.title());
            return null;
        });
    }

    public Editor editor(User u, long quizId) throws SQLException {
        try (Connection con = db.open()) {
            Quiz q = quizzes.findById(con, quizId).orElseThrow(() -> new NotFoundException("Quiz not found."));
            access.requireCourseProfessor(con, u, q.courseId());
            return new Editor(q, quizzes.questions(con, quizId), quizzes.hasAttempts(con, quizId));
        }
    }

    // =================================================================== lists

    /**
     * Quizzes the user can see, newest first. Drafts appear only for the course professor.
     * courseId / lectureId narrow the list (both optional).
     */
    public List<Quiz> list(User u, Long courseId, Long lectureId) throws SQLException {
        try (Connection con = db.open()) {
            List<Long> visible = access.visibleCourseIds(con, u);
            if (courseId != null) {
                visible = visible.contains(courseId) ? List.of(courseId) : List.of();
            }
            List<Long> drafts = new ArrayList<>();
            for (Long id : visible) {
                if (access.isCourseProfessor(con, u, id)) {
                    drafts.add(id);
                }
            }
            return quizzes.list(con, visible, drafts, lectureId, 300);
        }
    }

    /** A student's (or CR's) view of their quizzes, with attempt state. Finishes expired attempts first. */
    public List<StudentQuiz> forStudent(User u, Long courseId, Long lectureId) throws SQLException {
        requireLearner(u);
        return Tx.inTransaction(db, con -> {
            finalizeExpired(con, u.id(), null);
            List<Long> visible = access.visibleCourseIds(con, u);
            if (courseId != null) {
                visible = visible.contains(courseId) ? List.of(courseId) : List.of();
            }
            List<Quiz> list = quizzes.list(con, visible, List.of(), lectureId, 300);
            List<QuizAttempt> mine = attempts.forStudent(con, list.stream().map(Quiz::id).toList(), u.id());
            Instant now = attempts.dbNow(con);
            List<StudentQuiz> result = new ArrayList<>();
            for (Quiz q : list) {
                result.add(studentView(q, mine.stream().filter(a -> a.quizId() == q.id()).toList(), now));
            }
            return result;
        });
    }

    /** Open quizzes the student has not finished yet (dashboard "pending quizzes"). */
    public List<StudentQuiz> pending(User u) throws SQLException {
        return forStudent(u, null, null).stream()
                .filter(s -> s.state() == StudentState.OPEN || s.state() == StudentState.IN_PROGRESS)
                .sorted(java.util.Comparator.comparing(s -> s.quiz().closesAt()))
                .toList();
    }

    public StudentQuiz forStudent(User u, long quizId) throws SQLException {
        requireLearner(u);
        return Tx.inTransaction(db, con -> {
            finalizeExpired(con, u.id(), quizId);
            Quiz q = visibleQuiz(con, u, quizId);
            return studentView(q, attempts.forStudent(con, quizId, u.id()), attempts.dbNow(con));
        });
    }

    static StudentQuiz studentView(Quiz q, List<QuizAttempt> mine, Instant now) {
        QuizAttempt official = mine.stream().filter(a -> a.mode() == AttemptMode.OFFICIAL).findFirst().orElse(null);
        QuizAttempt open = mine.stream().filter(a -> a.status() == AttemptStatus.IN_PROGRESS).findFirst().orElse(null);
        boolean officialDone = official != null && official.status().finished();
        boolean closed = !now.isBefore(q.closesAt());
        boolean released = QuizRules.resultsReleased(q.reviewPolicy(), q.closesAt(), q.resultsReleasedAt(),
                officialDone || closed, now);
        StudentState state;
        if (official != null && !officialDone) {
            state = StudentState.IN_PROGRESS;
        } else if (officialDone) {
            state = released ? StudentState.RESULT_RELEASED : StudentState.AWAITING_RESULTS;
        } else if (now.isBefore(q.opensAt())) {
            state = StudentState.UPCOMING;
        } else if (!closed) {
            state = StudentState.OPEN;
        } else {
            state = StudentState.MISSED;
        }
        boolean practiceAllowed = q.allowPractice() && (officialDone || closed) && released;
        List<QuizAttempt> practice = mine.stream().filter(a -> a.mode() == AttemptMode.PRACTICE).toList();
        return new StudentQuiz(q, state, official == null ? null : mask(official, released), open == null ? null : mask(open, released),
                released, practiceAllowed, practice);
    }

    // =================================================================== attempts

    /**
     * Starts an attempt, or returns the attempt already in progress for the same mode (resume).
     * OFFICIAL: once per student, only while the quiz is open (checked against database time).
     * PRACTICE: only after results are released and the quiz allows practice; never counts academically.
     */
    public long start(User u, long quizId, AttemptMode mode) throws SQLException {
        requireLearner(u);
        try {
            return Tx.inTransaction(db, con -> {
                finalizeExpired(con, u.id(), quizId);
                Quiz q = quizzes.lockForShare(con, quizId).orElseThrow(() -> new NotFoundException("Quiz not found."));
                requireVisible(con, u, q);
                List<QuizAttempt> mine = attempts.forStudent(con, quizId, u.id());
                Optional<QuizAttempt> open = mine.stream().filter(a -> a.status() == AttemptStatus.IN_PROGRESS).findFirst();
                if (open.isPresent()) {
                    if (open.get().mode() == mode) {
                        return open.get().id();
                    }
                    throw new ValidationException("Finish your " + open.get().mode().label().toLowerCase() + " attempt first.");
                }
                if (mode == AttemptMode.OFFICIAL) {
                    if (mine.stream().anyMatch(a -> a.mode() == AttemptMode.OFFICIAL)) {
                        throw new ValidationException("You have already submitted this quiz.");
                    }
                } else if (!studentView(q, mine, attempts.dbNow(con)).practiceAllowed()) {
                    throw new ValidationException("Practice attempts are available only after results are released"
                            + (q.allowPractice() ? "." : ", and this quiz does not allow them."));
                }
                long id = attempts.start(con, quizId, u.id(), mode)
                        .orElseThrow(() -> new ValidationException("This quiz is not open right now."));
                audit.log(con, u.id(), "QUIZ_ATTEMPT_START", "quiz_attempt", id, mode.name());
                return id;
            });
        } catch (SQLException e) {
            if ("23505".equals(e.getSQLState())) {
                throw new ValidationException("You already have an attempt for this quiz. Refresh and try again.");
            }
            throw e;
        }
    }

    /** Loads (or resumes) an attempt. If the deadline has passed it is submitted first. */
    public AttemptSession session(User u, long attemptId) throws SQLException {
        return Tx.inTransaction(db, con -> {
            QuizAttempt a = ownAttempt(con, u, attemptId, true);
            Instant now = attempts.dbNow(con);
            if (a.status() == AttemptStatus.IN_PROGRESS && !now.isBefore(a.deadlineAt())) {
                a = finishLocked(con, a, now);
            }
            Quiz q = quizzes.findById(con, a.quizId()).orElseThrow();
            boolean released = released(q, a, now);
            if (a.status().finished()) {
                return new AttemptSession(q, mask(a, released), List.of(), Map.of(), 0);
            }
            long left = Math.max(0, Duration.between(now, a.deadlineAt()).getSeconds());
            return new AttemptSession(q, a, quizzes.studentQuestions(con, q.id()), attempts.savedAnswers(con, attemptId), left);
        });
    }

    /**
     * Autosaves one answer. Rejected after submission or once the database deadline has passed;
     * in that case the attempt is auto-submitted with the answers saved so far.
     */
    public void saveAnswer(User u, long attemptId, long questionId, long optionId) throws SQLException {
        boolean expired;
        try {
            expired = Tx.inTransaction(db, con -> {
                QuizAttempt a = ownAttempt(con, u, attemptId, true);
                if (a.status().finished()) {
                    throw new ValidationException("This attempt has already been submitted; answers can no longer change.");
                }
                Instant now = attempts.dbNow(con);
                if (!now.isBefore(a.deadlineAt())) {
                    finishLocked(con, a, now);
                    return true;
                }
                attempts.saveAnswer(con, attemptId, a.quizId(), questionId, optionId);
                return false;
            });
        } catch (SQLException e) {
            if ("23503".equals(e.getSQLState())) {
                throw new ValidationException("That answer does not belong to this quiz.");
            }
            throw e;
        }
        if (expired) {
            throw new ValidationException("Time is up. Your attempt was submitted automatically with the answers saved before the deadline.");
        }
    }

    /**
     * Submits an attempt. Idempotent: if it is already finished (double click, retry, timer and button
     * racing each other) the existing result is returned and nothing is scored again.
     */
    public AttemptResult submit(User u, long attemptId) throws SQLException {
        return Tx.inTransaction(db, con -> {
            QuizAttempt a = ownAttempt(con, u, attemptId, true);
            Instant now = attempts.dbNow(con);
            if (a.status() == AttemptStatus.IN_PROGRESS) {
                a = finishLocked(con, a, now);
                audit.log(con, u.id(), "QUIZ_SUBMIT", "quiz_attempt", attemptId, a.mode() + " " + a.status());
            }
            Quiz q = quizzes.findById(con, a.quizId()).orElseThrow();
            boolean released = released(q, a, now);
            return new AttemptResult(q, mask(a, released), released);
        });
    }

    /**
     * Read-only outcome of the student's own attempt (finishing it first only if its deadline has passed).
     * Unlike submit(), this never ends an attempt that still has time left.
     */
    public AttemptResult result(User u, long attemptId) throws SQLException {
        return Tx.inTransaction(db, con -> {
            QuizAttempt a = ownAttempt(con, u, attemptId, true);
            Instant now = attempts.dbNow(con);
            if (a.status() == AttemptStatus.IN_PROGRESS) {
                if (now.isBefore(a.deadlineAt())) {
                    throw new ValidationException("This attempt is still in progress.");
                }
                a = finishLocked(con, a, now);
            }
            Quiz q = quizzes.findById(con, a.quizId()).orElseThrow();
            boolean released = released(q, a, now);
            return new AttemptResult(q, mask(a, released), released);
        });
    }

    /** Correct answers and explanations — only for the student's own finished attempt, and only after release. */
    public AttemptReview review(User u, long attemptId) throws SQLException {
        try (Connection con = db.open()) {
            QuizAttempt a = ownAttempt(con, u, attemptId, false);
            if (!a.status().finished()) {
                throw new ValidationException("Submit the attempt before reviewing it.");
            }
            Quiz q = quizzes.findById(con, a.quizId()).orElseThrow();
            if (!released(q, a, attempts.dbNow(con))) {
                throw new AccessDeniedException("Results for this quiz have not been released yet.");
            }
            Map<Long, Question> questions = new java.util.LinkedHashMap<>();
            for (Question qu : quizzes.questions(con, q.id())) {
                questions.put(qu.id(), qu);
            }
            List<ReviewItem> items = new ArrayList<>();
            for (AttemptDao.AnswerRow row : attempts.answerRows(con, attemptId, q.id())) {
                items.add(new ReviewItem(questions.get(row.questionId()), row.chosenOptionId(), Boolean.TRUE.equals(row.correct())));
            }
            return new AttemptReview(q, a, items);
        }
    }

    // =================================================================== shared helpers (also used by analytics)

    /** Auto-submits in-progress attempts whose deadline has passed (for a student and/or a quiz). */
    void finalizeExpired(Connection con, Long studentId, Long quizId) throws SQLException {
        for (long id : attempts.expiredOpenAttempts(con, studentId, quizId)) {
            Optional<QuizAttempt> a = attempts.lockById(con, id);
            if (a.isPresent() && a.get().status() == AttemptStatus.IN_PROGRESS) {
                finishLocked(con, a.get(), attempts.dbNow(con));
            }
        }
    }

    /** Grades and finishes a locked IN_PROGRESS attempt: SUBMITTED before the deadline, EXPIRED after it. */
    private QuizAttempt finishLocked(Connection con, QuizAttempt a, Instant now) throws SQLException {
        int[] score = attempts.grade(con, a.id(), a.quizId());
        AttemptStatus status = now.isBefore(a.deadlineAt()) ? AttemptStatus.SUBMITTED : AttemptStatus.EXPIRED;
        attempts.finish(con, a.id(), status, score[0], score[1]);
        return attempts.findById(con, a.id()).orElseThrow();
    }

    private static boolean released(Quiz q, QuizAttempt a, Instant now) {
        if (a.mode() == AttemptMode.PRACTICE) {
            return a.status().finished();
        }
        return a.status().finished()
                && QuizRules.resultsReleased(q.reviewPolicy(), q.closesAt(), q.resultsReleasedAt(), true, now);
    }

    /** Hides the score of an attempt whose results are not released. */
    private static QuizAttempt mask(QuizAttempt a, boolean released) {
        if (released || a.mode() == AttemptMode.PRACTICE) {
            return a;
        }
        return new QuizAttempt(a.id(), a.quizId(), a.studentId(), a.attemptNo(), a.mode(), a.status(), a.startedAt(),
                a.deadlineAt(), a.submittedAt(), null, null);
    }

    private QuizAttempt ownAttempt(Connection con, User u, long attemptId, boolean lock) throws SQLException {
        Optional<QuizAttempt> a = lock ? attempts.lockById(con, attemptId) : attempts.findById(con, attemptId);
        // someone else's attempt id is reported as "not found", not "forbidden"
        if (a.isEmpty() || a.get().studentId() != u.id()) {
            throw new NotFoundException("Attempt not found.");
        }
        return a.get();
    }

    private Quiz visibleQuiz(Connection con, User u, long quizId) throws SQLException {
        Quiz q = quizzes.findById(con, quizId).orElseThrow(() -> new NotFoundException("Quiz not found."));
        requireVisible(con, u, q);
        return q;
    }

    private void requireVisible(Connection con, User u, Quiz q) throws SQLException {
        if (!access.isMember(con, u, q.courseId())
                || (q.status() != QuizStatus.PUBLISHED && !access.isCourseProfessor(con, u, q.courseId()))) {
            throw new NotFoundException("Quiz not found.");
        }
    }

    private static void requireLearner(User u) {
        if (!u.role().isLearner()) {
            throw new AccessDeniedException("Only students attempt quizzes.");
        }
    }

    private Quiz lockOwned(Connection con, User u, long quizId) throws SQLException {
        Quiz q = quizzes.lockForUpdate(con, quizId).orElseThrow(() -> new NotFoundException("Quiz not found."));
        access.requireCourseProfessor(con, u, q.courseId());
        return q;
    }

    /** Locks the quiz and refuses changes once any student has started an attempt. */
    private Quiz editable(Connection con, User u, long quizId) throws SQLException {
        Quiz q = lockOwned(con, u, quizId);
        if (quizzes.hasAttempts(con, quizId)) {
            throw new ValidationException("Students have already started this quiz, so its questions and settings are frozen.");
        }
        return q;
    }

    private void requireActiveCourse(Connection con, long courseId) throws SQLException {
        var c = courses.findById(con, courseId).orElseThrow(() -> new NotFoundException("Course not found."));
        if (c.archived()) {
            throw new AccessDeniedException("This course is archived.");
        }
    }

    private QuizDao.QuizData validate(Connection con, QuizForm f) throws SQLException {
        String title = Validation.text(f.title(), "Title", 3, 150);
        String instructions = Validation.optional(f.instructions(), "Instructions", 4000);
        int duration = Validation.range(f.durationMinutes(), "Duration (minutes)", 1, 300);
        if (f.openDate() == null || f.openTime() == null || f.closeDate() == null || f.closeTime() == null) {
            throw new ValidationException("Opening and closing date and time are required.");
        }
        if (f.reviewPolicy() == null) {
            throw new ValidationException("Choose when students can see results.");
        }
        Instant opens = f.openDate().atTime(f.openTime()).atZone(zone()).toInstant();
        Instant closes = f.closeDate().atTime(f.closeTime()).atZone(zone()).toInstant();
        if (!closes.isAfter(opens)) {
            throw new ValidationException("The closing time must be after the opening time.");
        }
        if (Duration.between(opens, closes).toDays() > 60) {
            throw new ValidationException("A quiz can stay open for at most 60 days.");
        }
        if (f.lectureId() != null) {
            Lecture l = lectures.findById(con, f.lectureId()).orElseThrow(() -> new NotFoundException("Lecture not found."));
            if (l.courseId() != f.courseId()) {
                throw new ValidationException("The selected lecture belongs to another course.");
            }
        }
        return new QuizDao.QuizData(f.courseId(), f.lectureId(), title, instructions, opens, closes, duration,
                f.reviewPolicy(), f.allowPractice());
    }

    QuizDao.QuestionData validate(Connection con, long courseId, QuestionForm f) throws SQLException {
        if (f.kind() == null) {
            throw new ValidationException("Choose a question type.");
        }
        String prompt = Validation.text(f.prompt(), "Question", 3, 2000);
        int marks = Validation.range(f.marks(), "Marks", 1, 100);
        String explanation = Validation.optional(f.explanation(), "Explanation", 2000);
        List<String> options;
        int correct = f.correctIndex();
        if (f.kind() == QuestionKind.TRUE_FALSE) {
            options = List.of("True", "False");
        } else {
            // blank option fields are skipped; the correct index is re-mapped to the remaining options
            options = new ArrayList<>();
            List<String> raw = f.options() == null ? List.of() : f.options();
            correct = -1;
            for (int i = 0; i < raw.size(); i++) {
                String o = raw.get(i);
                if (o != null && !o.isBlank()) {
                    if (i == f.correctIndex()) {
                        correct = options.size();
                    }
                    options.add(Validation.text(o, "Option", 1, 500));
                }
            }
            if (options.size() < 2 || options.size() > 6) {
                throw new ValidationException("A multiple-choice question needs 2 to 6 options.");
            }
            Set<String> seen = new HashSet<>();
            for (String o : options) {
                if (!seen.add(o.toLowerCase(Locale.ROOT))) {
                    throw new ValidationException("Two options have the same text: \"" + o + "\".");
                }
            }
        }
        if (correct < 0 || correct >= options.size()) {
            throw new ValidationException("Mark exactly one non-empty option as the correct answer.");
        }
        if (f.topicId() != null && courses.listTopics(con, courseId).stream().noneMatch(t -> t.id() == f.topicId())) {
            throw new ValidationException("The selected topic does not belong to this course.");
        }
        return new QuizDao.QuestionData(f.kind(), prompt, marks, f.topicId(), explanation, options, correct);
    }
}
