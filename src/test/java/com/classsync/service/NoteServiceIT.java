package com.classsync.service;

import com.classsync.Fixtures;
import com.classsync.TestDb;
import com.classsync.config.Database;
import com.classsync.model.CoursePermission;
import com.classsync.model.LectureStatus;
import com.classsync.model.Material;
import com.classsync.model.ReviewStatus;
import com.classsync.model.User;
import com.classsync.service.AppException.AccessDeniedException;
import com.classsync.service.AppException.NotFoundException;
import com.classsync.service.AppException.ValidationException;
import com.classsync.util.FileStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Student notes: review state machine, privacy of unapproved notes, self-approval, delegation, upvotes. */
class NoteServiceIT {

    @TempDir Path tmp;
    private Fixtures fx;
    private NoteService notes;
    private MaterialService materials;
    private NotificationService notifications;
    private CourseService courses;
    private Path uploads;
    private User prof;
    private User otherProf;
    private User asha;
    private User neha;
    private User cr;
    private long courseId;
    private long lectureId;

    @BeforeEach
    void setUp() throws Exception {
        Database db = TestDb.reset();
        fx = new Fixtures(db);
        AccessPolicy access = new AccessPolicy();
        uploads = tmp.resolve("uploads");
        FileStorage storage = new FileStorage(uploads, 1024 * 1024);
        LectureService lectures = new LectureService(db, access, Clock.system(ZoneId.of("Asia/Kolkata")));
        materials = new MaterialService(db, access, storage, lectures);
        notes = new NoteService(db, access, storage, lectures, materials);
        notifications = new NotificationService(db);
        courses = new CourseService(db, access);
        prof = fx.professor("Prof");
        otherProf = fx.professor("Other");
        asha = fx.student("Asha");
        neha = fx.student("Neha");
        cr = fx.cr("Ravi");
        courseId = fx.course(prof, "CS301");
        fx.enroll(courseId, asha, neha, cr);
        fx.staff(courseId, cr, prof, EnumSet.of(CoursePermission.REVIEW_NOTES));
        lectureId = fx.lecture(courseId, prof, 1, -2, LectureStatus.COMPLETED);
    }

    private Path pdf(String name) throws Exception {
        Path p = tmp.resolve(name);
        Files.writeString(p, "%PDF-1.4\n% notes\n");
        return p;
    }

    private long submit(User u, String title) throws Exception {
        return notes.submit(u, new NoteService.NoteForm(courseId, lectureId, title, "My handwritten notes", 1, pdf(title + ".pdf")));
    }

    @Test
    void pendingNotesAreVisibleOnlyToTheAuthorAndReviewers() throws Exception {
        long id = submit(asha, "Joins summary");
        assertEquals(ReviewStatus.PENDING, notes.mine(asha).get(0).note().reviewStatus());
        assertTrue(notes.approved(neha, null, null, null, "votes").isEmpty());
        assertTrue(notes.approved(neha, null, null, "Joins", "votes").isEmpty(), "not found by search either");
        assertThrows(NotFoundException.class, () -> materials.get(neha, id), "no direct access");
        assertThrows(NotFoundException.class, () -> materials.download(neha, id, tmp.resolve("x.pdf")));
        assertThrows(NotFoundException.class, () -> notes.history(neha, id));
        assertEquals(id, materials.get(asha, id).id(), "the author can open their own note");
        assertEquals(id, materials.get(cr, id).id(), "a delegated reviewer can inspect it");
        assertEquals(1, notes.reviewQueue(prof).size());
        assertEquals(1, notes.reviewQueue(cr).size());
        assertTrue(notes.reviewQueue(neha).isEmpty());
    }

    @Test
    void approvalPublishesRecordsHistoryAndNotifiesOnlyTheAuthor() throws Exception {
        long id = submit(asha, "Joins summary");
        notes.approve(prof, id);
        Material m = materials.get(neha, id);
        assertTrue(m.published());
        assertEquals(ReviewStatus.APPROVED, m.reviewStatus());
        assertEquals(List.of("SUBMITTED", "APPROVED"), notes.history(asha, id).stream().map(h -> h.action()).toList());
        assertEquals(1, notifications.unreadCount(asha));
        assertEquals(0, notifications.unreadCount(neha), "classmates are not told about review decisions");
        assertThrows(ValidationException.class, () -> notes.approve(prof, id), "already reviewed");
        assertThrows(ValidationException.class, () -> notes.resubmit(asha, id, "x", "", null), "approved notes are final");
    }

    @Test
    void rejectionNeedsAReasonAndResubmissionReturnsToReview() throws Exception {
        long id = submit(asha, "Joins draft");
        assertThrows(ValidationException.class, () -> notes.reject(prof, id, " "));
        notes.reject(prof, id, "Pages 2-3 are unreadable");
        var mine = notes.mine(asha).get(0);
        assertEquals(ReviewStatus.REJECTED, mine.note().reviewStatus());
        assertEquals("Pages 2-3 are unreadable", mine.lastComment());
        String oldPath = mine.note().storedPath();

        notes.resubmit(asha, id, "Joins (rescanned)", "Clearer scan", pdf("rescan.pdf"));
        Material again = materials.get(asha, id);
        assertEquals(ReviewStatus.PENDING, again.reviewStatus());
        assertEquals("rescan.pdf", again.originalFilename());
        assertFalse(Files.exists(uploads.resolve(oldPath)), "the replaced file is removed");
        assertTrue(Files.exists(uploads.resolve(again.storedPath())));
        assertEquals(List.of("SUBMITTED", "REJECTED", "RESUBMITTED"), notes.history(prof, id).stream().map(h -> h.action()).toList());
        assertThrows(NotFoundException.class, () -> notes.resubmit(neha, id, "hijack", "", null), "only the author resubmits");
    }

    @Test
    void nobodyApprovesTheirOwnNoteEvenAReviewerCr() throws Exception {
        long own = submit(cr, "Ravi's notes");
        assertTrue(notes.reviewQueue(cr).isEmpty(), "own notes never appear in one's queue");
        AccessDeniedException e = assertThrows(AccessDeniedException.class, () -> notes.approve(cr, own));
        assertTrue(e.getMessage().contains("own"));
        notes.approve(prof, own);   // another reviewer can
    }

    @Test
    void revokedDelegationAndOutsidersAreRefused() throws Exception {
        long id = submit(asha, "Joins summary");
        assertThrows(AccessDeniedException.class, () -> notes.approve(neha, id), "plain student");
        assertThrows(AccessDeniedException.class, () -> notes.approve(otherProf, id), "professor of another course");
        courses.setStaffPermissions(prof, courseId, cr.id(), EnumSet.noneOf(CoursePermission.class));
        assertThrows(AccessDeniedException.class, () -> notes.approve(cr, id), "revocation applies on the next call");
        assertThrows(AccessDeniedException.class, () -> notes.submit(prof,
                new NoteService.NoteForm(courseId, null, "Prof notes", "", null, pdf("p.pdf"))), "professors upload materials, not notes");
    }

    @Test
    void upvotesAreUniqueAndNotForOwnOrPendingNotes() throws Exception {
        long id = submit(asha, "Joins summary");
        assertThrows(NotFoundException.class, () -> notes.vote(neha, id, true), "pending notes cannot be voted");
        notes.approve(prof, id);
        notes.vote(neha, id, true);
        notes.vote(neha, id, true);
        notes.vote(cr, id, true);
        assertEquals(2, materials.get(asha, id).votes(), "one vote per person");
        assertThrows(ValidationException.class, () -> notes.vote(asha, id, true), "no self-upvote");
        assertThrows(AccessDeniedException.class, () -> notes.vote(prof, id, true), "professors recommend instead");
        notes.vote(neha, id, false);
        assertEquals(1, materials.get(asha, id).votes());
        assertTrue(notes.myVotes(cr).contains(id));
    }

    @Test
    void helpfulRankingOrdersByVotes() throws Exception {
        long a = submit(asha, "Alpha notes");
        long b = submit(neha, "Beta notes");
        notes.approve(prof, a);
        notes.approve(prof, b);
        notes.vote(cr, b, true);
        notes.vote(asha, b, true);
        assertEquals(List.of(b, a), notes.approved(cr, courseId, null, null, "votes").stream().map(Material::id).toList());
    }
}
