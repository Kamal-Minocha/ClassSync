package com.classsync.service;

import com.classsync.Fixtures;
import com.classsync.TestDb;
import com.classsync.config.Database;
import com.classsync.dao.CourseDao;
import com.classsync.dao.StudyDao.Target;
import com.classsync.model.CoursePermission;
import com.classsync.model.LectureStatus;
import com.classsync.model.MaterialKind;
import com.classsync.model.User;
import com.classsync.service.AppException.AccessDeniedException;
import com.classsync.service.AppException.NotFoundException;
import com.classsync.service.AppException.ValidationException;
import com.classsync.service.PlannerService.Status;
import com.classsync.util.FileStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Semester planner status/completion and private bookmarks and study tasks. */
class PlannerAndStudyIT {

    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    @TempDir Path tmp;
    private Database db;
    private Fixtures fx;
    private PlannerService planner;
    private StudyService study;
    private MaterialService materials;
    private NoteService notes;
    private CourseService courses;
    private User prof;
    private User asha;
    private User neha;
    private User cr;
    private User outsider;
    private long courseId;
    private long[] topics;

    @BeforeEach
    void setUp() throws Exception {
        db = TestDb.reset();
        fx = new Fixtures(db);
        AccessPolicy access = new AccessPolicy();
        Clock clock = Clock.system(ZONE);
        LectureService lectures = new LectureService(db, access, clock);
        FileStorage storage = new FileStorage(tmp.resolve("u"), 1 << 20);
        materials = new MaterialService(db, access, storage, lectures);
        notes = new NoteService(db, access, storage, lectures, materials);
        planner = new PlannerService(db, access, clock);
        study = new StudyService(db, access, materials, lectures, new DiscussionService(db, access, lectures));
        courses = new CourseService(db, access);
        prof = fx.professor("Prof");
        asha = fx.student("Asha");
        neha = fx.student("Neha");
        cr = fx.cr("Ravi");
        outsider = fx.student("Outsider");
        courseId = fx.course(prof, "CS301");
        fx.enroll(courseId, asha, neha, cr);
        fx.staff(courseId, cr, prof, EnumSet.allOf(CoursePermission.class));
        topics = new long[5];
        try (Connection con = db.open()) {
            CourseDao dao = new CourseDao();
            String[] names = {"Relational model", "SQL basics", "Joins", "Normalization", "Indexing"};
            for (int i = 0; i < 5; i++) {
                topics[i] = dao.insertTopic(con, courseId, i < 2 ? 1 : 2, names[i], i + 1);
            }
        }
    }

    private long lectureOn(long topicId, int number, int hoursFromNow, LectureStatus status) throws SQLException {
        long id = fx.lecture(courseId, prof, number, hoursFromNow, status);
        try (Connection con = db.open(); PreparedStatement ps = con.prepareStatement("UPDATE lectures SET topic_id = ? WHERE id = ?")) {
            ps.setLong(1, topicId);
            ps.setLong(2, id);
            ps.executeUpdate();
        }
        return id;
    }

    // ------------------------------------------------------------------ planner

    @Test
    void topicStatusIsDerivedFromRealLecturesAndPlans() throws SQLException {
        LocalDate today = LocalDate.now(ZONE);
        lectureOn(topics[0], 1, -48, LectureStatus.COMPLETED);
        lectureOn(topics[1], 2, 48, LectureStatus.PUBLISHED);
        planner.setPlan(prof, topics[2], today.minusDays(3), 2, 2, "");
        planner.setPlan(prof, topics[3], today.plusDays(10), 3, 2, "Use the invoice example");
        lectureOn(topics[4], 3, -24, LectureStatus.CANCELLED);   // a cancelled lecture covers nothing

        var plan = planner.plan(prof, courseId);
        assertEquals(List.of(Status.COMPLETED, Status.SCHEDULED, Status.OVERDUE, Status.PLANNED, Status.UNPLANNED),
                plan.rows().stream().map(PlannerService.PlanRow::status).toList());
        assertEquals(1, plan.completed());
        assertEquals(0.2, plan.completion().orElseThrow(), 1e-9);
        assertEquals("Use the invoice example", plan.rows().get(3).notes());
        assertEquals(2, plan.rows().get(1).nextLecture().lectureNo());

        planner.setPlan(prof, topics[2], today.plusDays(1), 2, 2, "rescheduled");
        assertEquals(Status.PLANNED, planner.plan(prof, courseId).rows().get(2).status(), "rescheduling clears OVERDUE");
    }

    @Test
    void emptySyllabusHasNoPercentageAndMembersCanReadButOnlyTheProfessorEdits() throws SQLException {
        long empty = fx.course(prof, "CS999");
        assertTrue(planner.plan(prof, empty).completion().isEmpty());
        assertEquals(5, planner.plan(asha, courseId).rows().size(), "students see syllabus progress");
        assertThrows(AccessDeniedException.class, () -> planner.plan(outsider, courseId));
        assertThrows(AccessDeniedException.class, () -> planner.setPlan(cr, topics[0], null, 1, 1, ""), "CR with every permission");
        assertThrows(AccessDeniedException.class, () -> planner.move(asha, topics[1], true));
    }

    @Test
    void topicsMoveWithinTheirUnitOnly() throws SQLException {
        planner.move(prof, topics[1], true);
        assertEquals(List.of("SQL basics", "Relational model", "Joins", "Normalization", "Indexing"),
                planner.plan(prof, courseId).rows().stream().map(r -> r.topic().name()).toList());
        assertThrows(ValidationException.class, () -> planner.move(prof, topics[0], false), "cannot leave unit 1");
        planner.setPlan(prof, topics[4], null, 1, 3, "");   // change unit
        assertEquals(3, planner.plan(prof, courseId).rows().get(4).topic().unitNo());
    }

    // ------------------------------------------------------------------ bookmarks and tasks

    private long material(boolean publish) throws Exception {
        Path f = tmp.resolve("m" + System.nanoTime() + ".pdf");
        Files.writeString(f, "%PDF-1.4\n");
        return materials.upload(prof, new MaterialService.UploadForm(courseId, null, MaterialKind.MATERIAL, "Slides", "", null, f, publish));
    }

    @Test
    void bookmarksArePrivateAndOnlyForVisibleItems() throws Exception {
        long published = material(true);
        long hidden = material(false);
        long draft = fx.lecture(courseId, prof, 7, 24, LectureStatus.DRAFT);
        study.bookmark(asha, Target.MATERIAL, published, true);
        study.bookmark(asha, Target.MATERIAL, published, true);   // duplicate is ignored
        assertThrows(NotFoundException.class, () -> study.bookmark(asha, Target.MATERIAL, hidden, true));
        assertThrows(NotFoundException.class, () -> study.bookmark(asha, Target.LECTURE, draft, true));
        assertThrows(NotFoundException.class, () -> study.bookmark(outsider, Target.MATERIAL, published, true),
                "outsiders are not even told the file exists");
        Path n = tmp.resolve("note.pdf");
        Files.writeString(n, "%PDF-1.4\n");
        long pendingNote = notes.submit(neha, new NoteService.NoteForm(courseId, null, "Neha's notes", "", null, n));
        assertThrows(NotFoundException.class, () -> study.bookmark(asha, Target.MATERIAL, pendingNote, true),
                "another student's pending note cannot be bookmarked");

        assertEquals(1, study.bookmarks(asha).size());
        assertTrue(study.isBookmarked(asha, Target.MATERIAL, published));
        assertTrue(study.bookmarks(neha).isEmpty(), "bookmarks are private");
        study.bookmark(asha, Target.MATERIAL, published, false);
        assertTrue(study.bookmarks(asha).isEmpty());
    }

    @Test
    void bookmarksDisappearWhenAccessIsLost() throws Exception {
        long m = material(true);
        study.bookmark(asha, Target.MATERIAL, m, true);
        materials.setPublished(prof, m, false);
        assertTrue(study.bookmarks(asha).isEmpty(), "unpublished item is no longer listed");
        materials.setPublished(prof, m, true);
        assertEquals(1, study.bookmarks(asha).size());
        courses.unenroll(prof, courseId, asha.id());
        assertTrue(study.bookmarks(asha).isEmpty(), "unenrolled: the bookmark is not a back door");
    }

    @Test
    void bookmarkRowsHaveExactlyOneTarget() {
        SQLException e = assertThrows(SQLException.class, () -> {
            try (Connection con = db.open(); PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO bookmarks (user_id) VALUES (?)")) {
                ps.setLong(1, asha.id());
                ps.executeUpdate();
            }
        });
        assertEquals("23514", e.getSQLState(), "CHECK num_nonnulls(...) = 1");
    }

    @Test
    void studyTasksArePrivate() throws SQLException {
        long t = study.addTask(asha, "Revise joins", LocalDate.now(ZONE).plusDays(2), courseId);
        assertThrows(ValidationException.class, () -> study.addTask(asha, " ", null, null));
        assertThrows(AccessDeniedException.class, () -> study.addTask(outsider, "Spy", null, courseId));
        assertThrows(NotFoundException.class, () -> study.setTaskDone(neha, t, true), "someone else's task");
        assertThrows(NotFoundException.class, () -> study.deleteTask(neha, t));
        assertTrue(study.tasks(neha).isEmpty());
        study.setTaskDone(asha, t, true);
        assertNotNull(study.tasks(asha).get(0).doneAt());
        study.setTaskDone(asha, t, false);
        assertNull(study.tasks(asha).get(0).doneAt());
        study.deleteTask(asha, t);
        assertTrue(study.tasks(asha).isEmpty());
    }
}
