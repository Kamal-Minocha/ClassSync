package com.classsync.service;

import com.classsync.Fixtures;
import com.classsync.TestDb;
import com.classsync.config.AppConfig;
import com.classsync.config.Database;
import com.classsync.model.AttendanceStatus;
import com.classsync.model.CoursePermission;
import com.classsync.model.LectureStatus;
import com.classsync.model.User;
import com.classsync.service.AppException.AccessDeniedException;
import com.classsync.service.AppException.ValidationException;
import com.classsync.service.AttendanceService.LiveCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/** Temporary-code attendance against real PostgreSQL: codes, expiry, finalization, corrections, races. */
class AttendanceServiceIT {

    private Database db;
    private Fixtures fx;
    private AttendanceService attendance;
    private User prof;
    private User otherProf;
    private User asha;
    private User neha;
    private User cr;
    private User outsider;
    private long courseId;
    private long lecture;   // started an hour ago, published

    @BeforeEach
    void setUp() throws SQLException {
        db = TestDb.reset();
        fx = new Fixtures(db);
        attendance = new AttendanceService(db, new AccessPolicy(), 120);
        prof = fx.professor("Prof");
        otherProf = fx.professor("Other");
        asha = fx.student("Asha");
        neha = fx.student("Neha");
        cr = fx.cr("Ravi");
        outsider = fx.student("Outsider");
        courseId = fx.course(prof, "CS301");
        fx.enroll(courseId, asha, neha, cr);
        fx.staff(courseId, cr, prof, EnumSet.allOf(CoursePermission.class));
        lecture = fx.lecture(courseId, prof, 1, -1, LectureStatus.PUBLISHED);
    }

    // ------------------------------------------------------------------ helpers

    private void sql(String sql, Object... args) throws SQLException {
        try (Connection con = db.open(); PreparedStatement ps = con.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.executeUpdate();
        }
    }

    private String text(String sql, Object... args) throws SQLException {
        try (Connection con = db.open(); PreparedStatement ps = con.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private AttendanceStatus statusOf(User u) throws SQLException {
        String s = text("SELECT status FROM attendance WHERE lecture_id = ? AND student_id = ?", lecture, u.id());
        return s == null ? null : AttendanceStatus.valueOf(s);
    }

    private static String wrong(String code) {
        return code.charAt(0) == 'A' ? "B" + code.substring(1) : "A" + code.substring(1);
    }

    // ------------------------------------------------------------------ codes

    @Test
    void correctCodeMarksPresentAndTheCodeIsNeverStoredOrLogged() throws SQLException {
        LiveCode live = attendance.start(prof, lecture);
        assertEquals(AttendanceService.CODE_LENGTH, live.code().length());
        assertTrue(live.secondsLeft() > 100 && live.secondsLeft() <= 120);

        attendance.submitCode(asha, live.sessionId(), " " + live.code().toLowerCase() + " ");   // spaces and case forgiven
        assertEquals(AttendanceStatus.PRESENT, statusOf(asha));

        String stored = text("SELECT code_hash FROM attendance_sessions WHERE id = ?", live.sessionId());
        assertNotEquals(live.code(), stored);
        assertFalse(stored.contains(live.code()));
        assertEquals("0", text("SELECT count(*) FROM audit_logs WHERE details LIKE ?", "%" + live.code() + "%"),
                "the code never appears in the audit log");
    }

    @Test
    void wrongCodesAreCountedAndLimited() throws SQLException {
        LiveCode live = attendance.start(prof, lecture);
        for (int i = 0; i < AttendanceService.MAX_WRONG_CODES; i++) {
            ValidationException e = assertThrows(ValidationException.class,
                    () -> attendance.submitCode(asha, live.sessionId(), wrong(live.code())));
            assertTrue(e.getMessage().contains("not correct"));
        }
        assertEquals(String.valueOf(AttendanceService.MAX_WRONG_CODES),
                text("SELECT failures FROM attendance_code_failures WHERE student_id = ?", asha.id()),
                "failures are committed even though the call failed");
        ValidationException locked = assertThrows(ValidationException.class,
                () -> attendance.submitCode(asha, live.sessionId(), live.code()));
        assertTrue(locked.getMessage().contains("Too many"), "even the right code is refused after the limit");
        assertNull(statusOf(asha));

        attendance.submitCode(neha, live.sessionId(), live.code());   // other students are unaffected
        assertEquals(AttendanceStatus.PRESENT, statusOf(neha));
    }

    @Test
    void expiredCodeIsRejectedAndANewCodeReplacesTheOldOne() throws SQLException {
        LiveCode first = attendance.start(prof, lecture);
        sql("UPDATE attendance_sessions SET started_at = now() - interval '10 minutes', code_expires_at = now() - interval '1 second' WHERE id = ?",
                first.sessionId());
        ValidationException expired = assertThrows(ValidationException.class,
                () -> attendance.submitCode(asha, first.sessionId(), first.code()));
        assertTrue(expired.getMessage().contains("expired"));
        assertEquals(null, text("SELECT failures FROM attendance_code_failures WHERE student_id = ?", asha.id()),
                "an expired but correct code is not counted as a wrong guess");

        LiveCode second = attendance.newCode(prof, first.sessionId());
        assertEquals(first.sessionId(), second.sessionId());
        if (!second.code().equals(first.code())) {
            assertThrows(ValidationException.class, () -> attendance.submitCode(asha, first.sessionId(), first.code()),
                    "the old code stops working at once");
        }
        attendance.submitCode(asha, second.sessionId(), second.code());
        assertEquals(AttendanceStatus.PRESENT, statusOf(asha));
    }

    @Test
    void onlyEnrolledStudentsCanSubmit() throws SQLException {
        LiveCode live = attendance.start(prof, lecture);
        assertThrows(AccessDeniedException.class, () -> attendance.submitCode(outsider, live.sessionId(), live.code()));
        assertThrows(AccessDeniedException.class, () -> attendance.submitCode(prof, live.sessionId(), live.code()));
        assertNull(text("SELECT status FROM attendance WHERE student_id = ?", outsider.id()));
    }

    @Test
    void duplicateSubmissionsAreRejectedByJavaAndByTheDatabase() throws SQLException {
        LiveCode live = attendance.start(prof, lecture);
        attendance.submitCode(asha, live.sessionId(), live.code());
        ValidationException again = assertThrows(ValidationException.class,
                () -> attendance.submitCode(asha, live.sessionId(), live.code()));
        assertTrue(again.getMessage().contains("already marked"));
        SQLException pk = assertThrows(SQLException.class, () -> sql(
                "INSERT INTO attendance (lecture_id, student_id, status, source, session_id) VALUES (?, ?, 'PRESENT', 'CODE', ?)",
                lecture, asha.id(), live.sessionId()));
        assertEquals("23505", pk.getSQLState(), "primary key (lecture_id, student_id)");
    }

    @Test
    void concurrentSubmitsOfOneStudentCreateOneRecord() throws Exception {
        LiveCode live = attendance.start(prof, lecture);
        List<Object> results = race(6, () -> {
            try {
                attendance.submitCode(asha, live.sessionId(), live.code());
                return "ok";
            } catch (ValidationException e) {
                return e;
            }
        });
        assertEquals(1, results.stream().filter("ok"::equals).count());
        assertEquals("1", text("SELECT count(*) FROM attendance WHERE student_id = ?", asha.id()));
    }

    @Test
    void oneOpenSessionPerLecture() throws SQLException {
        LiveCode a = attendance.start(prof, lecture);
        LiveCode b = attendance.start(prof, lecture);
        assertEquals(a.sessionId(), b.sessionId(), "starting again reuses the open session with a fresh code");
        SQLException e = assertThrows(SQLException.class, () -> sql(
                "INSERT INTO attendance_sessions (lecture_id, started_by, code_hash, code_expires_at) VALUES (?, ?, repeat('a', 64), now() + interval '1 minute')",
                lecture, prof.id()));
        assertEquals("23505", e.getSQLState(), "partial unique index attendance_sessions_one_open");

        attendance.close(prof, a.sessionId());
        assertThrows(ValidationException.class, () -> attendance.submitCode(asha, a.sessionId(), b.code()), "closed session");
        assertNotEquals(a.sessionId(), attendance.start(prof, lecture).sessionId(), "a new session can be opened after closing");
    }

    @Test
    void startRules() throws SQLException {
        long draft = fx.lecture(courseId, prof, 2, -1, LectureStatus.DRAFT);
        assertThrows(ValidationException.class, () -> attendance.start(prof, draft));
        long cancelled = fx.lecture(courseId, prof, 3, -1, LectureStatus.CANCELLED);
        assertThrows(ValidationException.class, () -> attendance.start(prof, cancelled));
        long tomorrow = fx.lecture(courseId, prof, 4, 24, LectureStatus.PUBLISHED);
        assertThrows(ValidationException.class, () -> attendance.start(prof, tomorrow), "too early");
        assertThrows(ValidationException.class, () -> attendance.finalizeAttendance(prof, tomorrow), "not started yet");
    }

    // ------------------------------------------------------------------ finalization

    @Test
    void finalizationRecordsAbsenteesAndStopsSubmissions() throws SQLException {
        LiveCode live = attendance.start(prof, lecture);
        attendance.submitCode(asha, live.sessionId(), live.code());
        int[] counts = attendance.finalizeAttendance(prof, lecture);
        assertArrayEquals(new int[]{1, 2}, counts, "Asha present; Neha and Ravi absent");
        assertEquals(AttendanceStatus.ABSENT, statusOf(neha));
        assertEquals("FINALIZE", text("SELECT source FROM attendance WHERE lecture_id = ? AND student_id = ?", lecture, neha.id()));
        assertEquals("CLOSED", text("SELECT status FROM attendance_sessions WHERE id = ?", live.sessionId()));

        ValidationException late = assertThrows(ValidationException.class,
                () -> attendance.submitCode(neha, live.sessionId(), live.code()));
        assertTrue(late.getMessage().contains("finalized") || late.getMessage().contains("closed"));
        assertEquals(AttendanceStatus.ABSENT, statusOf(neha));
        assertThrows(ValidationException.class, () -> attendance.finalizeAttendance(prof, lecture), "only once");
        assertThrows(ValidationException.class, () -> attendance.start(prof, lecture), "no new session after finalizing");
    }

    /**
     * Students submit while the professor finalizes. Whatever the interleaving, every student ends with exactly
     * one record, and it matches what their own call reported: success = PRESENT, refused = ABSENT.
     */
    @Test
    void concurrentFinalizeAndSubmitsStayConsistent() throws Exception {
        List<User> students = new ArrayList<>(List.of(asha, neha, cr));
        for (int i = 0; i < 9; i++) {
            User s = fx.student("Extra " + i);
            fx.enroll(courseId, s);
            students.add(s);
        }
        for (int round = 0; round < 3; round++) {
            long lec = fx.lecture(courseId, prof, 10 + round, -1, LectureStatus.PUBLISHED);
            LiveCode live = attendance.start(prof, lec);
            ExecutorService pool = Executors.newFixedThreadPool(students.size() + 1);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Boolean>> submits = new ArrayList<>();
            for (User s : students) {
                submits.add(pool.submit(() -> {
                    go.await();
                    try {
                        attendance.submitCode(s, live.sessionId(), live.code());
                        return true;
                    } catch (ValidationException e) {
                        return false;
                    }
                }));
            }
            Future<int[]> finalize = pool.submit(() -> {
                go.await();
                return attendance.finalizeAttendance(prof, lec);
            });
            go.countDown();
            int presentReported = 0;
            for (int i = 0; i < students.size(); i++) {
                boolean ok = submits.get(i).get();
                String status = text("SELECT status FROM attendance WHERE lecture_id = ? AND student_id = ?", lec, students.get(i).id());
                assertEquals(ok ? "PRESENT" : "ABSENT", status, "student " + i + " in round " + round);
                presentReported += ok ? 1 : 0;
            }
            int[] counts = finalize.get();
            pool.shutdown();
            assertEquals(presentReported, counts[0] + 0, "finalize saw exactly the submissions that committed before it");
            assertEquals(students.size(), counts[0] + counts[1]);
            assertEquals(String.valueOf(students.size()), text("SELECT count(*) FROM attendance WHERE lecture_id = ?", lec));
        }
    }

    // ------------------------------------------------------------------ corrections

    @Test
    void correctionStoresActorReasonAndBeforeAfter() throws SQLException {
        attendance.finalizeAttendance(prof, lecture);
        assertThrows(ValidationException.class, () -> attendance.correct(prof, lecture, neha.id(), AttendanceStatus.PRESENT, " "),
                "a reason is required");
        assertThrows(ValidationException.class, () -> attendance.correct(prof, lecture, neha.id(), AttendanceStatus.ABSENT, "Already absent"),
                "no-op correction refused");
        attendance.correct(prof, lecture, neha.id(), AttendanceStatus.PRESENT, "Was in the lab; code screen not visible");
        assertEquals(AttendanceStatus.PRESENT, statusOf(neha));
        var corrections = attendance.lecture(prof, lecture).corrections();
        assertEquals(1, corrections.size());
        var c = corrections.get(0);
        assertEquals(AttendanceStatus.ABSENT, c.oldStatus());
        assertEquals(AttendanceStatus.PRESENT, c.newStatus());
        assertEquals("Prof", c.correctedByName());
        assertEquals("Was in the lab; code screen not visible", c.reason());
        assertEquals("1", text("SELECT count(*) FROM audit_logs WHERE action = 'ATTENDANCE_CORRECT'"));
    }

    @Test
    void correctionIsAllOrNothing() throws SQLException {
        attendance.finalizeAttendance(prof, lecture);
        // make the correction-history insert fail inside the transaction
        sql("""
                CREATE FUNCTION fail_correction() RETURNS trigger LANGUAGE plpgsql AS
                $$ BEGIN RAISE EXCEPTION 'simulated failure'; END $$""");
        sql("CREATE TRIGGER fail_correction BEFORE INSERT ON attendance_corrections FOR EACH ROW EXECUTE FUNCTION fail_correction()");
        assertThrows(SQLException.class,
                () -> attendance.correct(prof, lecture, neha.id(), AttendanceStatus.PRESENT, "Medical certificate"));
        assertEquals(AttendanceStatus.ABSENT, statusOf(neha), "the status change was rolled back with the failed history row");
        assertEquals("0", text("SELECT count(*) FROM audit_logs WHERE action = 'ATTENDANCE_CORRECT'"));
    }

    @Test
    void correctionsNeedFinalizationAndTheCourseProfessor() throws SQLException {
        LiveCode live = attendance.start(prof, lecture);
        attendance.submitCode(asha, live.sessionId(), live.code());
        assertThrows(ValidationException.class,
                () -> attendance.correct(prof, lecture, asha.id(), AttendanceStatus.ABSENT, "Left early today"), "not finalized yet");
        attendance.finalizeAttendance(prof, lecture);
        assertThrows(AccessDeniedException.class,
                () -> attendance.correct(cr, lecture, asha.id(), AttendanceStatus.ABSENT, "CR tries to correct"));
        assertThrows(AccessDeniedException.class,
                () -> attendance.correct(otherProf, lecture, asha.id(), AttendanceStatus.ABSENT, "Wrong professor"));
    }

    @Test
    void crAndOtherProfessorsCannotRunSessionsOrFinalize() throws SQLException {
        assertThrows(AccessDeniedException.class, () -> attendance.start(cr, lecture), "CR with every delegable permission");
        assertThrows(AccessDeniedException.class, () -> attendance.start(otherProf, lecture));
        assertThrows(AccessDeniedException.class, () -> attendance.finalizeAttendance(cr, lecture));
        assertThrows(AccessDeniedException.class, () -> attendance.lecture(cr, lecture));
        LiveCode live = attendance.start(prof, lecture);
        assertThrows(AccessDeniedException.class, () -> attendance.newCode(cr, live.sessionId()));
        assertThrows(AccessDeniedException.class, () -> attendance.close(otherProf, live.sessionId()));
        attendance.submitCode(cr, live.sessionId(), live.code());   // a CR marks their own attendance like any student
        assertEquals(AttendanceStatus.PRESENT, statusOf(cr));
    }

    // ------------------------------------------------------------------ percentages

    @Test
    void percentagesCountOnlyFinalizedNonCancelledLectures() throws SQLException {
        var empty = attendance.summary(asha);
        assertTrue(empty.overall().isEmpty(), "no finalized lectures → no percentage, not 0%");

        // L1: present
        LiveCode live = attendance.start(prof, lecture);
        attendance.submitCode(asha, live.sessionId(), live.code());
        attendance.finalizeAttendance(prof, lecture);
        // L2: absent
        long l2 = fx.lecture(courseId, prof, 2, -3, LectureStatus.COMPLETED);
        attendance.finalizeAttendance(prof, l2);
        // L3: finalized then (by data repair) cancelled → not counted
        long l3 = fx.lecture(courseId, prof, 3, -5, LectureStatus.COMPLETED);
        attendance.finalizeAttendance(prof, l3);
        sql("UPDATE lectures SET status = 'CANCELLED' WHERE id = ?", l3);
        // L4: past but not finalized → not counted
        fx.lecture(courseId, prof, 4, -7, LectureStatus.COMPLETED);

        var s = attendance.summary(asha);
        assertEquals(2, s.counted());
        assertEquals(1, s.present());
        assertEquals(0.5, s.overall().orElseThrow(), 1e-9);
        assertEquals(2, s.history().size());
        assertTrue(AttendanceService.isLow(s.present(), s.counted()));

        var report = attendance.courseReport(prof, courseId);
        assertEquals(2, report.lectures().size());
        assertEquals(1, report.awaitingFinalization(), "L4 still needs finalizing");
        assertEquals(1.0 / 6, report.average().orElseThrow(), 1e-9, "1 present out of 3 students × 2 lectures");
        assertThrows(AccessDeniedException.class, () -> attendance.courseReport(cr, courseId));
    }

    // ------------------------------------------------------------------ restricted role

    @Test
    void wholeFlowWorksWithTheRestrictedApplicationRoleAndCorrectionsAreAppendOnly() throws SQLException {
        AppConfig cfg = TestDb.config();
        String appPassword = cfg.get(AppConfig.APP_PASSWORD);
        org.junit.jupiter.api.Assumptions.assumeTrue(appPassword != null, "app password not configured");
        Database appDb = new Database(cfg.require(AppConfig.TEST_DB_URL), cfg.require(AppConfig.APP_USER), appPassword);
        AttendanceService asApp = new AttendanceService(appDb, new AccessPolicy(), 60);
        LiveCode live = asApp.start(prof, lecture);
        asApp.submitCode(asha, live.sessionId(), live.code());
        assertThrows(ValidationException.class, () -> asApp.submitCode(neha, live.sessionId(), wrong(live.code())));
        asApp.finalizeAttendance(prof, lecture);
        asApp.correct(prof, lecture, neha.id(), AttendanceStatus.PRESENT, "Joined late with permission");
        assertEquals(1, asApp.lecture(prof, lecture).corrections().size());
        try (Connection con = appDb.open(); var st = con.createStatement()) {
            SQLException upd = assertThrows(SQLException.class, () -> st.executeUpdate("UPDATE attendance_corrections SET reason = 'edited'"));
            assertEquals("42501", upd.getSQLState(), "history cannot be rewritten by the app");
            SQLException del = assertThrows(SQLException.class, () -> st.executeUpdate("DELETE FROM attendance_corrections"));
            assertEquals("42501", del.getSQLState());
        }
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
}
