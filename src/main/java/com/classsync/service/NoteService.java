package com.classsync.service;

import com.classsync.config.Database;
import com.classsync.config.Tx;
import com.classsync.dao.AuditDao;
import com.classsync.dao.MaterialDao;
import com.classsync.dao.NoteDao;
import com.classsync.dao.NotificationDao;
import com.classsync.model.CoursePermission;
import com.classsync.model.Lecture;
import com.classsync.model.Material;
import com.classsync.model.MaterialKind;
import com.classsync.model.ReviewStatus;
import com.classsync.model.User;
import com.classsync.service.AppException.AccessDeniedException;
import com.classsync.service.AppException.NotFoundException;
import com.classsync.service.AppException.ValidationException;
import com.classsync.util.FileStorage;
import com.classsync.util.Validation;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Student notes and their review.
 *
 * State machine: PENDING → APPROVED or REJECTED; a resubmission (new details and/or a new file) returns the
 * note to PENDING. Only APPROVED notes are visible to classmates. Authors always see their own notes;
 * reviewers (course professor, or a CR delegated REVIEW_NOTES) see pending notes in the review queue.
 * Nobody reviews their own note. Every transition is written to material_approvals in the same transaction,
 * and the author is notified of each decision (only the author — other students never learn about it).
 */
public class NoteService {

    public record NoteForm(long courseId, Long lectureId, String title, String description, Integer unitNo, Path file) {
    }

    /** A note in the author's "My submissions" list with the latest reviewer comment. */
    public record MyNote(Material note, String lastComment) {
    }

    private final Database db;
    private final AccessPolicy access;
    private final FileStorage storage;
    private final LectureService lectures;
    private final MaterialService materials;
    private final MaterialDao materialDao = new MaterialDao();
    private final NoteDao notes = new NoteDao();
    private final NotificationDao notifications = new NotificationDao();
    private final AuditDao audit = new AuditDao();

    public NoteService(Database db, AccessPolicy access, FileStorage storage, LectureService lectures, MaterialService materials) {
        this.db = db;
        this.access = access;
        this.storage = storage;
        this.lectures = lectures;
        this.materials = materials;
    }

    // ---------------------------------------------------------------- author

    /** Uploads a note for review. Only enrolled students and CRs submit notes. */
    public long submit(User u, NoteForm f) throws SQLException, IOException {
        requireLearner(u);
        String title = Validation.text(f.title(), "Title", 2, 150);
        String description = Validation.optional(f.description(), "Description", 2000);
        if (f.unitNo() != null) {
            Validation.range(f.unitNo(), "Unit", 1, 10);
        }
        try (Connection con = db.open()) {
            access.requireMember(con, u, f.courseId());
            checkLecture(con, u, f.courseId(), f.lectureId());
        }
        FileStorage.StoredFile stored = storage.store(f.file(), "notes");
        try {
            return Tx.inTransaction(db, con -> {
                access.requireMember(con, u, f.courseId());
                long id = materialDao.insert(con, new MaterialDao.NewMaterial(f.courseId(), f.lectureId(), MaterialKind.NOTE,
                        title, description, f.unitNo(), u.id(), stored.displayName(), stored.relativePath(),
                        stored.fileType(), stored.sizeBytes(), false, ReviewStatus.PENDING));
                notes.addHistory(con, id, u.id(), "SUBMITTED", "");
                audit.log(con, u.id(), "NOTE_SUBMIT", "material", id, "");
                return id;
            });
        } catch (SQLException | RuntimeException e) {
            storage.deleteQuietly(stored.relativePath());
            throw e;
        }
    }

    /** The author edits a pending or rejected note (optionally replacing the file); it goes back to review. */
    public void resubmit(User u, long noteId, String title, String description, Path newFile) throws SQLException, IOException {
        String t = Validation.text(title, "Title", 2, 150);
        String d = Validation.optional(description, "Description", 2000);
        FileStorage.StoredFile stored = newFile == null ? null : storage.store(newFile, "notes");
        String[] oldPath = new String[1];
        try {
            Tx.inTransaction(db, con -> {
                Material n = lockNote(con, noteId);
                if (n.uploaderId() != u.id()) {
                    throw new NotFoundException("Note not found.");
                }
                if (n.reviewStatus() == ReviewStatus.APPROVED) {
                    throw new ValidationException("Approved notes cannot be changed. Submit a new note instead.");
                }
                oldPath[0] = n.storedPath();
                notes.resubmit(con, noteId, t, d, stored == null ? null : stored.displayName(),
                        stored == null ? null : stored.relativePath(), stored == null ? null : stored.fileType(),
                        stored == null ? null : stored.sizeBytes());
                notes.addHistory(con, noteId, u.id(), "RESUBMITTED", "");
                audit.log(con, u.id(), "NOTE_RESUBMIT", "material", noteId, "");
                return null;
            });
        } catch (SQLException | RuntimeException e) {
            if (stored != null) {
                storage.deleteQuietly(stored.relativePath());
            }
            throw e;
        }
        if (stored != null) {
            storage.deleteQuietly(oldPath[0]);   // the replaced file, only after the commit succeeded
        }
    }

    public List<MyNote> mine(User u) throws SQLException {
        try (Connection con = db.open()) {
            List<MyNote> list = new ArrayList<>();
            for (Material m : notes.mine(con, u.id())) {
                list.add(new MyNote(m, notes.latestComment(con, m.id())));
            }
            return list;
        }
    }

    // ---------------------------------------------------------------- reviewers

    /** Pending notes the user may review (never their own). */
    public List<Material> reviewQueue(User u) throws SQLException {
        try (Connection con = db.open()) {
            List<Long> courses = new ArrayList<>();
            for (Long id : access.visibleCourseIds(con, u)) {
                if (access.has(con, u, id, CoursePermission.REVIEW_NOTES)) {
                    courses.add(id);
                }
            }
            return notes.reviewQueue(con, courses, u.id());
        }
    }

    public void approve(User u, long noteId) throws SQLException {
        review(u, noteId, ReviewStatus.APPROVED, "");
    }

    public void reject(User u, long noteId, String comment) throws SQLException {
        review(u, noteId, ReviewStatus.REJECTED, Validation.text(comment, "Rejection reason", 5, 1000));
    }

    private void review(User u, long noteId, ReviewStatus decision, String comment) throws SQLException {
        Tx.inTransaction(db, con -> {
            Material n = lockNote(con, noteId);
            // permission is read fresh, so a revoked CR is refused on the very next attempt
            access.require(con, u, n.courseId(), CoursePermission.REVIEW_NOTES);
            if (n.uploaderId() == u.id()) {
                throw new AccessDeniedException("You cannot review your own note.");
            }
            if (n.reviewStatus() != ReviewStatus.PENDING) {
                throw new ValidationException("This note was already " + n.reviewStatus().name().toLowerCase()
                        + ". Refresh the review queue.");
            }
            notes.setStatus(con, noteId, decision);
            long historyId = notes.addHistory(con, noteId, u.id(), decision.name(), comment);
            audit.log(con, u.id(), "NOTE_" + decision.name(), "material", noteId, "");
            notifications.notifyUsers(con, List.of(n.uploaderId()), new NotificationDao.Message("NOTE",
                    decision == ReviewStatus.APPROVED ? "Your note was approved: " + n.title() : "Your note needs changes: " + n.title(),
                    decision == ReviewStatus.APPROVED ? "Classmates can now see and download it." : comment,
                    "NOTE", noteId, "note-review:" + historyId));
            return null;
        });
    }

    /** Review history — for the author and for reviewers only. */
    public List<NoteDao.HistoryRow> history(User u, long noteId) throws SQLException {
        try (Connection con = db.open()) {
            Material n = materialDao.findById(con, noteId).filter(m -> m.kind() == MaterialKind.NOTE)
                    .orElseThrow(() -> new NotFoundException("Note not found."));
            if (n.uploaderId() != u.id() && !access.has(con, u, n.courseId(), CoursePermission.REVIEW_NOTES)) {
                throw new NotFoundException("Note not found.");
            }
            return notes.history(con, noteId);
        }
    }

    // ---------------------------------------------------------------- classmates

    /** Approved notes in the user's courses (optionally one course/lecture), ranked by the chosen sort. */
    public List<Material> approved(User u, Long courseId, Long lectureId, String text, String sort) throws SQLException {
        return materials.list(u, new MaterialService.Query(courseId, lectureId, List.of(MaterialKind.NOTE), text,
                sort == null ? "votes" : sort, 300)).stream().filter(Material::published).toList();
    }

    public Set<Long> myVotes(User u) throws SQLException {
        try (Connection con = db.open()) {
            return notes.votedBy(con, u.id());
        }
    }

    /** One upvote per person per approved note (database primary key); authors cannot upvote their own. */
    public void vote(User u, long noteId, boolean up) throws SQLException {
        if (!u.role().isLearner()) {
            throw new AccessDeniedException("Professors recommend notes instead of upvoting them.");
        }
        Tx.inTransaction(db, con -> {
            Material n = materials.visible(con, u, noteId);
            if (n.kind() != MaterialKind.NOTE || !n.published()) {
                throw new ValidationException("Only approved notes can be upvoted.");
            }
            if (n.uploaderId() == u.id()) {
                throw new ValidationException("You cannot upvote your own note.");
            }
            if (up) {
                notes.vote(con, noteId, u.id());
            } else {
                notes.unvote(con, noteId, u.id());
            }
            return null;
        });
    }

    // ---------------------------------------------------------------- helpers

    private Material lockNote(Connection con, long noteId) throws SQLException {
        return materialDao.lockById(con, noteId).filter(m -> m.kind() == MaterialKind.NOTE && m.archivedAt() == null)
                .orElseThrow(() -> new NotFoundException("Note not found."));
    }

    private void checkLecture(Connection con, User u, long courseId, Long lectureId) throws SQLException {
        if (lectureId != null) {
            Lecture l = lectures.visibleLecture(con, u, lectureId);
            if (l.courseId() != courseId) {
                throw new ValidationException("The lecture belongs to a different course.");
            }
        }
    }

    private static void requireLearner(User u) {
        if (!u.role().isLearner()) {
            throw new AccessDeniedException("Notes are submitted by students. Professors upload course materials instead.");
        }
    }
}
