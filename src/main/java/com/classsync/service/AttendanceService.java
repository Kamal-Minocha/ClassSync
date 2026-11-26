package com.classsync.service;

import com.classsync.config.Database;
import com.classsync.config.Tx;
import com.classsync.dao.AttendanceDao;
import com.classsync.dao.AuditDao;
import com.classsync.dao.CourseDao;
import com.classsync.dao.LectureDao;
import com.classsync.dao.NotificationDao;
import com.classsync.model.AttendanceCorrection;
import com.classsync.model.AttendanceRecord;
import com.classsync.model.AttendanceSession;
import com.classsync.model.AttendanceStatus;
import com.classsync.model.Lecture;
import com.classsync.model.LectureStatus;
import com.classsync.model.User;
import com.classsync.service.AppException.AccessDeniedException;
import com.classsync.service.AppException.NotFoundException;
import com.classsync.service.AppException.ValidationException;
import com.classsync.util.Validation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Temporary-code attendance.
 *
 * Flow: the professor starts a session for a lecture and shows a random 6-character code that expires
 * after a configurable number of seconds (a new one can be shown at any time). Enrolled students type it
 * in. The professor reviews who is present and finalizes: every enrolled student without a record becomes
 * ABSENT and no more codes are accepted. After finalization only the professor can correct a record, with
 * a reason, and the before/after values are stored in the same transaction.
 *
 * What counts: lectures whose attendance is finalized and which are not cancelled. A student's percentage is
 * present ÷ counted lectures in which they have a record. No counted lectures → no percentage.
 *
 * Honest limits: a code discourages casual misuse but cannot prove physical presence (a friend can pass the
 * code on), which is why the professor reviews and can correct. The wrong-code limit is enforced by this
 * desktop client against database counters; a modified client could skip it.
 */
public class AttendanceService {

    /** Below this fraction a student gets a low-attendance warning. */
    public static final double LOW_ATTENDANCE = 0.75;
    /** Wrong codes allowed per student per session. */
    public static final int MAX_WRONG_CODES = 5;
    public static final int CODE_LENGTH = 6;
    /** No 0/O, 1/I/L: easy to read from a projector and to type. */
    static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";

    /** Returned only to the professor who starts a session or asks for a new code. The code is never stored. */
    public record LiveCode(long sessionId, String code, Instant expiresAt, long secondsLeft) {
    }

    /** Professor view of one lecture's attendance. */
    public record LectureAttendance(Lecture lecture, AttendanceSession openSession, List<AttendanceRecord> records,
                                    List<AttendanceCorrection> corrections) {

        public long present() {
            return records.stream().filter(r -> r.status() == AttendanceStatus.PRESENT).count();
        }
    }

    /** Student view of one lecture: their record, or the open session they can submit a code to. */
    public record MyLecture(AttendanceStatus status, AttendanceSession openSession, boolean finalized) {
    }

    /** Per-course and overall attendance of one student. */
    public record StudentSummary(List<AttendanceDao.CourseTotals> courses, int counted, int present,
                                 List<AttendanceDao.HistoryRow> history) {

        public OptionalDouble overall() {
            return fraction(present, counted);
        }
    }

    /** Course report for the professor. */
    public record CourseReport(List<AttendanceDao.LectureTotals> lectures, List<AttendanceDao.StudentTotals> students,
                               int awaitingFinalization) {

        public OptionalDouble average() {
            int present = lectures.stream().mapToInt(AttendanceDao.LectureTotals::present).sum();
            int total = lectures.stream().mapToInt(AttendanceDao.LectureTotals::total).sum();
            return fraction(present, total);
        }
    }

    /** One course line on the professor dashboard. */
    public record CourseOverview(long courseId, String courseCode, OptionalDouble average, int finalizedLectures,
                                 int awaitingFinalization, int lowAttendanceStudents) {
    }

    private enum Outcome { MARKED, WRONG, EXPIRED, LOCKED_OUT }

    private final Database db;
    private final AccessPolicy access;
    private final int codeSeconds;
    private final SecureRandom random = new SecureRandom();
    private final AttendanceDao dao = new AttendanceDao();
    private final LectureDao lectures = new LectureDao();
    private final CourseDao courses = new CourseDao();
    private final AuditDao audit = new AuditDao();
    private final NotificationDao notifications = new NotificationDao();

    public AttendanceService(Database db, AccessPolicy access, int codeSeconds) {
        this.db = db;
        this.access = access;
        this.codeSeconds = Validation.range(codeSeconds, "Attendance code lifetime (seconds)", 15, 900);
    }

    public int codeSeconds() {
        return codeSeconds;
    }

    // =================================================================== professor: sessions

    /**
     * Starts an attendance session for a published lecture (from 30 minutes before it begins), or — if one is
     * already open — shows a fresh code for it.
     */
    public LiveCode start(User u, long lectureId) throws SQLException {
        try {
            return Tx.inTransaction(db, con -> {
                Lecture l = lectures.lockById(con, lectureId).orElseThrow(() -> new NotFoundException("Lecture not found."));
                access.requireCourseProfessor(con, u, l.courseId());
                requireActiveCourse(con, l.courseId());
                if (l.attendanceFinalized()) {
                    throw new ValidationException("Attendance for this lecture is already finalized.");
                }
                if (l.status() != LectureStatus.PUBLISHED && l.status() != LectureStatus.COMPLETED) {
                    throw new ValidationException("Attendance can only be taken for a published or completed lecture.");
                }
                Instant now = dao.dbNow(con);
                if (now.isBefore(l.startsAt().minus(Duration.ofMinutes(30)))) {
                    throw new ValidationException("Attendance opens 30 minutes before the lecture starts.");
                }
                String code = newCode();
                Optional<AttendanceSession> open = dao.openSessionForLecture(con, lectureId);
                long sessionId;
                if (open.isPresent()) {
                    sessionId = open.get().id();
                    dao.rotateCode(con, sessionId, hash(lectureId, code), codeSeconds);
                } else {
                    sessionId = dao.insertSession(con, lectureId, u.id(), hash(lectureId, code), codeSeconds);
                    // the code itself is deliberately NOT written to the audit log
                    audit.log(con, u.id(), "ATTENDANCE_SESSION_START", "lecture", lectureId, "session " + sessionId);
                }
                return live(con, sessionId, code);
            });
        } catch (SQLException e) {
            if ("23505".equals(e.getSQLState())) {
                throw new ValidationException("An attendance session was just opened for this lecture. Refresh and try again.");
            }
            throw e;
        }
    }

    /** Replaces the code of an open session (e.g. when it expired or leaked). The old code stops working at once. */
    public LiveCode newCode(User u, long sessionId) throws SQLException {
        return Tx.inTransaction(db, con -> {
            AttendanceSession s = dao.lockSession(con, sessionId).orElseThrow(() -> new NotFoundException("Session not found."));
            access.requireCourseProfessor(con, u, s.courseId());
            if (!s.open()) {
                throw new ValidationException("This attendance session is closed.");
            }
            String code = newCode();
            dao.rotateCode(con, sessionId, hash(s.lectureId(), code), codeSeconds);
            return live(con, sessionId, code);
        });
    }

    /** Stops accepting codes without finalizing (the professor can still finalize or start again later). */
    public void close(User u, long sessionId) throws SQLException {
        Tx.inTransaction(db, con -> {
            AttendanceSession s = dao.lockSession(con, sessionId).orElseThrow(() -> new NotFoundException("Session not found."));
            access.requireCourseProfessor(con, u, s.courseId());
            dao.closeSession(con, sessionId);
            audit.log(con, u.id(), "ATTENDANCE_SESSION_CLOSE", "lecture", s.lectureId(), "session " + sessionId);
            return null;
        });
    }

    /**
     * Finalizes a lecture's attendance in one transaction: closes any open session, records every enrolled
     * student without a record as ABSENT, and stamps the lecture. Returns {present, absent}.
     * The lecture row is locked FOR UPDATE, so it cannot interleave with a student's code submission.
     */
    public int[] finalizeAttendance(User u, long lectureId) throws SQLException {
        return Tx.inTransaction(db, con -> {
            Lecture l = lectures.lockById(con, lectureId).orElseThrow(() -> new NotFoundException("Lecture not found."));
            access.requireCourseProfessor(con, u, l.courseId());
            if (l.attendanceFinalized()) {
                throw new ValidationException("Attendance for this lecture is already finalized.");
            }
            if (l.status() != LectureStatus.PUBLISHED && l.status() != LectureStatus.COMPLETED) {
                throw new ValidationException("Only published or completed lectures can have attendance.");
            }
            if (dao.dbNow(con).isBefore(l.startsAt())) {
                throw new ValidationException("Attendance can be finalized once the lecture has started.");
            }
            dao.closeOpenSessions(con, lectureId);
            int absent = dao.insertAbsentees(con, lectureId, l.courseId());
            dao.setFinalized(con, lectureId, u.id());
            int present = dao.presentCount(con, lectureId);
            audit.log(con, u.id(), "ATTENDANCE_FINALIZE", "lecture", lectureId, present + " present, " + absent + " absent");
            // warn students who are now below the threshold in this course (one warning per finalized lecture)
            for (AttendanceDao.StudentTotals s : dao.studentTotalsForCourse(con, l.courseId())) {
                if (isLow(s.present(), s.counted())) {
                    notifications.notifyUsers(con, List.of(s.studentId()), new NotificationDao.Message("ATTENDANCE",
                            "Low attendance in " + l.courseCode() + ": " + Math.round(100.0 * s.present() / s.counted()) + "%",
                            "You attended " + s.present() + " of " + s.counted() + " counted lectures. The expected minimum is "
                                    + Math.round(LOW_ATTENDANCE * 100) + "%.",
                            "ATTENDANCE", l.courseId(), "attendance-low:" + lectureId));
                }
            }
            return new int[]{present, absent};
        });
    }

    /**
     * Professor correction after finalization. The record change, the correction row (actor, time, reason,
     * old and new value) and the audit entry are written in ONE transaction.
     */
    public void correct(User u, long lectureId, long studentId, AttendanceStatus newStatus, String reason) throws SQLException {
        String why = Validation.text(reason, "Reason", 5, 500);
        if (newStatus == null) {
            throw new ValidationException("Choose the corrected status.");
        }
        Tx.inTransaction(db, con -> {
            Lecture l = lectures.lockById(con, lectureId).orElseThrow(() -> new NotFoundException("Lecture not found."));
            access.requireCourseProfessor(con, u, l.courseId());
            if (!l.attendanceFinalized()) {
                throw new ValidationException("Corrections are made after attendance is finalized.");
            }
            AttendanceStatus old = dao.status(con, lectureId, studentId, true)
                    .orElseThrow(() -> new ValidationException("This student has no attendance record for the lecture."));
            if (old == newStatus) {
                throw new ValidationException("The student is already marked " + old.label().toLowerCase() + ".");
            }
            dao.updateStatus(con, lectureId, studentId, newStatus);
            dao.insertCorrection(con, lectureId, studentId, old, newStatus, why, u.id());
            audit.log(con, u.id(), "ATTENDANCE_CORRECT", "lecture", lectureId, "student " + studentId + ": " + old + " -> " + newStatus);
            return null;
        });
    }

    /** Roster with statuses, the open session (without its code) and correction history. */
    public LectureAttendance lecture(User u, long lectureId) throws SQLException {
        try (Connection con = db.open()) {
            Lecture l = lectures.findById(con, lectureId).orElseThrow(() -> new NotFoundException("Lecture not found."));
            access.requireCourseProfessor(con, u, l.courseId());
            return new LectureAttendance(l, dao.openSessionForLecture(con, lectureId).orElse(null),
                    dao.roster(con, lectureId, l.courseId()), dao.corrections(con, lectureId));
        }
    }

    // =================================================================== student

    /**
     * Marks the student present if the code is right, unexpired, the session is open, the lecture is not
     * finalized and the student is enrolled. A wrong code is counted; after MAX_WRONG_CODES the student must
     * ask the professor.
     */
    public void submitCode(User u, long sessionId, String rawCode) throws SQLException {
        if (!u.role().isLearner()) {
            throw new AccessDeniedException("Only students mark attendance.");
        }
        String code = rawCode == null ? "" : rawCode.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
        if (code.isEmpty()) {
            throw new ValidationException("Enter the code shown by your professor.");
        }
        Outcome outcome;
        try {
            outcome = Tx.inTransaction(db, con -> {
                AttendanceSession found = dao.findSession(con, sessionId).orElseThrow(() -> new NotFoundException("Session not found."));
                // lock order: lecture, then session — the same order finalize uses
                Lecture l = lectures.lockShared(con, found.lectureId()).orElseThrow();
                if (!access.isMember(con, u, l.courseId())) {
                    throw new AccessDeniedException("You are not enrolled in " + l.courseCode() + ".");
                }
                if (l.attendanceFinalized()) {
                    throw new ValidationException("Attendance for this lecture is already finalized.");
                }
                AttendanceSession s = dao.lockSessionShared(con, sessionId).orElseThrow();
                if (!s.open()) {
                    throw new ValidationException("This attendance session is closed.");
                }
                if (dao.status(con, l.id(), u.id(), false).isPresent()) {
                    throw new ValidationException("You are already marked present for this lecture.");
                }
                if (dao.failures(con, sessionId, u.id()) >= MAX_WRONG_CODES) {
                    return Outcome.LOCKED_OUT;
                }
                boolean matches = MessageDigest.isEqual(hash(l.id(), code).getBytes(StandardCharsets.US_ASCII),
                        dao.codeHash(con, sessionId).getBytes(StandardCharsets.US_ASCII));
                if (!matches) {
                    dao.recordFailure(con, sessionId, u.id());
                    return Outcome.WRONG;
                }
                if (!dao.dbNow(con).isBefore(s.codeExpiresAt())) {
                    return Outcome.EXPIRED;
                }
                if (!dao.markPresent(con, l.id(), u.id(), sessionId)) {
                    throw new ValidationException("You are already marked present for this lecture.");
                }
                return Outcome.MARKED;
            });
        } catch (SQLException e) {
            if ("23505".equals(e.getSQLState())) {
                throw new ValidationException("You are already marked present for this lecture.");
            }
            throw e;
        }
        // the wrong-code counter above is committed before these messages are shown
        switch (outcome) {
            case MARKED -> { }
            case WRONG -> throw new ValidationException("That code is not correct. Check the code on the screen and try again.");
            case EXPIRED -> throw new ValidationException("That code has expired. Ask your professor to show the new code.");
            case LOCKED_OUT -> throw new ValidationException("Too many wrong codes for this session. Ask your professor to mark you.");
        }
    }

    /** Open sessions in the student's courses that still await their code, oldest first. */
    public List<AttendanceSession> openSessions(User u) throws SQLException {
        try (Connection con = db.open()) {
            return dao.openSessions(con, access.visibleCourseIds(con, u), u.id());
        }
    }

    public MyLecture myLecture(User u, long lectureId) throws SQLException {
        try (Connection con = db.open()) {
            Lecture l = lectures.findById(con, lectureId).orElseThrow(() -> new NotFoundException("Lecture not found."));
            access.requireMember(con, u, l.courseId());
            AttendanceSession open = l.attendanceFinalized() ? null : dao.openSessionForLecture(con, lectureId).orElse(null);
            return new MyLecture(dao.status(con, lectureId, u.id(), false).orElse(null), open, l.attendanceFinalized());
        }
    }

    public StudentSummary summary(User u) throws SQLException {
        if (!u.role().isLearner()) {
            throw new AccessDeniedException("Attendance summaries are for students.");
        }
        try (Connection con = db.open()) {
            List<Long> ids = access.visibleCourseIds(con, u);
            List<AttendanceDao.CourseTotals> totals = dao.studentTotals(con, u.id(), ids);
            int counted = totals.stream().mapToInt(AttendanceDao.CourseTotals::counted).sum();
            int present = totals.stream().mapToInt(AttendanceDao.CourseTotals::present).sum();
            return new StudentSummary(totals, counted, present, dao.history(con, u.id(), ids, 200));
        }
    }

    // =================================================================== professor reports

    public CourseReport courseReport(User u, long courseId) throws SQLException {
        try (Connection con = db.open()) {
            access.requireCourseProfessor(con, u, courseId);
            return new CourseReport(dao.lectureTotals(con, courseId), dao.studentTotalsForCourse(con, courseId),
                    dao.awaitingFinalization(con, courseId));
        }
    }

    public List<CourseOverview> overview(User u) throws SQLException {
        try (Connection con = db.open()) {
            List<CourseOverview> list = new java.util.ArrayList<>();
            for (Long id : access.visibleCourseIds(con, u)) {
                if (!access.isCourseProfessor(con, u, id)) {
                    continue;
                }
                var c = courses.findById(con, id).orElseThrow();
                var lectureTotals = dao.lectureTotals(con, id);
                CourseReport r = new CourseReport(lectureTotals, List.of(), 0);
                int low = (int) dao.studentTotalsForCourse(con, id).stream()
                        .filter(s -> isLow(s.present(), s.counted())).count();
                list.add(new CourseOverview(id, c.code(), r.average(), lectureTotals.size(),
                        dao.awaitingFinalization(con, id), low));
            }
            return list;
        }
    }

    // =================================================================== rules and helpers

    /** present ÷ counted, or empty when nothing is counted yet (never 0 ÷ 0). */
    public static OptionalDouble fraction(int present, int counted) {
        return counted <= 0 ? OptionalDouble.empty() : OptionalDouble.of((double) present / counted);
    }

    /** A warning needs at least one counted lecture. */
    public static boolean isLow(int present, int counted) {
        return counted > 0 && (double) present / counted < LOW_ATTENDANCE;
    }

    String newCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    /** SHA-256 of lecture id + code. Keeps codes out of plain sight in the database and backups. */
    static String hash(long lectureId, String code) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((lectureId + ":" + code).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private LiveCode live(Connection con, long sessionId, String code) throws SQLException {
        AttendanceSession s = dao.findSession(con, sessionId).orElseThrow();
        long left = Math.max(0, Duration.between(dao.dbNow(con), s.codeExpiresAt()).getSeconds());
        return new LiveCode(sessionId, code, s.codeExpiresAt(), left);
    }

    private void requireActiveCourse(Connection con, long courseId) throws SQLException {
        var c = courses.findById(con, courseId).orElseThrow(() -> new NotFoundException("Course not found."));
        if (c.archived()) {
            throw new AccessDeniedException("This course is archived.");
        }
    }
}
