package com.classsync.service;

import com.classsync.config.Database;
import com.classsync.config.Tx;
import com.classsync.dao.AuditDao;
import com.classsync.dao.MaterialDao;
import com.classsync.dao.NotificationDao;
import com.classsync.model.CoursePermission;
import com.classsync.model.Lecture;
import com.classsync.model.Material;
import com.classsync.model.MaterialKind;
import com.classsync.model.Role;
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
import java.util.List;

/**
 * Course materials (professor / CR uploads). Every read — listing, opening, downloading —
 * goes through the same access rules.
 */
public class MaterialService {

    public record UploadForm(long courseId, Long lectureId, MaterialKind kind, String title, String description,
                             Integer unitNo, Path file, boolean publish) {
    }

    public record Query(Long courseId, Long lectureId, List<MaterialKind> kinds, String text, String sort, int limit) {
    }

    private final Database db;
    private final AccessPolicy access;
    private final FileStorage storage;
    private final LectureService lectures;
    private final MaterialDao materials = new MaterialDao();
    private final AuditDao audit = new AuditDao();
    private final NotificationDao notifications = new NotificationDao();

    public MaterialService(Database db, AccessPolicy access, FileStorage storage, LectureService lectures) {
        this.db = db;
        this.access = access;
        this.storage = storage;
        this.lectures = lectures;
    }

    public FileStorage storage() {
        return storage;
    }

    /**
     * Upload: validate, copy the file into the managed folder, then insert metadata.
     * If the database insert fails the copied file is deleted, so no orphan files and no
     * metadata pointing at a missing file are left behind.
     */
    public long upload(User u, UploadForm f) throws SQLException, IOException {
        if (f.kind() == MaterialKind.NOTE) {
            throw new ValidationException("Student notes are submitted from the Notes page.");
        }
        String title = Validation.text(f.title(), "Title", 2, 150);
        String description = Validation.optional(f.description(), "Description", 2000);
        if (f.unitNo() != null) {
            Validation.range(f.unitNo(), "Unit", 1, 10);
        }
        // permission check before touching the file system
        boolean canPublish;
        try (Connection con = db.open()) {
            access.require(con, u, f.courseId(), CoursePermission.UPLOAD_MATERIALS);
            canPublish = access.has(con, u, f.courseId(), CoursePermission.ORGANIZE_RESOURCES);
            if (f.lectureId() != null) {
                Lecture l = lectures.visibleLecture(con, u, f.lectureId());
                if (l.courseId() != f.courseId()) {
                    throw new ValidationException("The lecture belongs to a different course.");
                }
            }
        }
        FileStorage.StoredFile stored = storage.store(f.file(), "materials");
        try {
            return Tx.inTransaction(db, con -> {
                // re-check inside the transaction: permission may have been revoked meanwhile
                access.require(con, u, f.courseId(), CoursePermission.UPLOAD_MATERIALS);
                long id = materials.insert(con, new MaterialDao.NewMaterial(f.courseId(), f.lectureId(), f.kind(),
                        title, description, f.unitNo(), u.id(), stored.displayName(), stored.relativePath(),
                        stored.fileType(), stored.sizeBytes(), f.publish() && canPublish, null));
                audit.log(con, u.id(), "MATERIAL_UPLOAD", "material", id, stored.displayName());
                if (f.publish() && canPublish) {
                    notifyPublished(con, u, f.courseId(), id, title);
                }
                return id;
            });
        } catch (SQLException | RuntimeException e) {
            storage.deleteQuietly(stored.relativePath());
            throw e;
        }
    }

    /** Lists materials the user may see (published, or unpublished for staff, or their own). */
    public List<Material> list(User u, Query q) throws SQLException {
        try (Connection con = db.open()) {
            List<Long> visible = access.visibleCourseIds(con, u);
            if (q.courseId() != null) {
                if (!visible.contains(q.courseId())) {
                    throw new AccessDeniedException("You are not a member of this course.");
                }
                visible = List.of(q.courseId());
            }
            List<Long> staff = access.staffCourseIds(con, u);
            List<MaterialKind> kinds = q.kinds() == null || q.kinds().isEmpty()
                    ? List.of(MaterialKind.MATERIAL, MaterialKind.PYQ, MaterialKind.REFERENCE) : q.kinds();
            String text = q.text() == null ? null : Validation.optional(q.text(), "Search text", 100);
            int limit = Math.min(Math.max(q.limit(), 1), 500);
            return materials.list(con, new MaterialDao.Filter(visible, staff, u.id(), kinds, q.lectureId(), text,
                    q.sort(), limit));
        }
    }

    public Material get(User u, long materialId) throws SQLException {
        try (Connection con = db.open()) {
            return visible(con, u, materialId);
        }
    }

    Material visible(Connection con, User u, long materialId) throws SQLException {
        Material m = materials.findById(con, materialId).orElseThrow(() -> new NotFoundException("File not found."));
        if (!canView(con, u, m)) {
            // do not reveal whether a private record exists
            throw new NotFoundException("File not found.");
        }
        return m;
    }

    boolean canView(Connection con, User u, Material m) throws SQLException {
        if (!access.isMember(con, u, m.courseId())) {
            return false;
        }
        if (m.uploaderId() == u.id()) {
            return true;
        }
        if (m.archivedAt() != null) {
            return access.isCourseProfessor(con, u, m.courseId());
        }
        if (m.published()) {
            return true;
        }
        return m.kind() == MaterialKind.NOTE
                ? access.has(con, u, m.courseId(), CoursePermission.REVIEW_NOTES)
                : access.has(con, u, m.courseId(), CoursePermission.VIEW_SCHEDULE);
    }

    /** Save-as: copies the stored file to the chosen destination after an access check. */
    public void download(User u, long materialId, Path destination) throws SQLException, IOException {
        Material m;
        try (Connection con = db.open()) {
            m = visible(con, u, materialId);
        }
        storage.exportTo(m.storedPath(), destination);
        countDownload(m);
    }

    /** Resolves the managed file for opening with the operating system's default application. */
    public Path pathForOpening(User u, long materialId) throws SQLException, IOException {
        Material m;
        try (Connection con = db.open()) {
            m = visible(con, u, materialId);
        }
        Path p = storage.resolve(m.storedPath());
        countDownload(m);
        return p;
    }

    private void countDownload(Material m) throws SQLException {
        try (Connection con = db.open()) {
            materials.incrementDownloads(con, m.id());
        }
    }

    public void setPublished(User u, long materialId, boolean published) throws SQLException {
        Tx.inTransaction(db, con -> {
            Material m = materials.lockById(con, materialId).orElseThrow(() -> new NotFoundException("File not found."));
            if (m.kind() == MaterialKind.NOTE) {
                throw new ValidationException("Notes are published by approving them.");
            }
            access.require(con, u, m.courseId(), CoursePermission.ORGANIZE_RESOURCES);
            materials.setPublished(con, materialId, published);
            audit.log(con, u.id(), published ? "MATERIAL_PUBLISH" : "MATERIAL_UNPUBLISH", "material", materialId, "");
            if (published && !m.published()) {
                notifyPublished(con, u, m.courseId(), materialId, m.title());
            }
            return null;
        });
    }

    public void updateDetails(User u, long materialId, String title, String description, Long lectureId, Integer unitNo)
            throws SQLException {
        String t = Validation.text(title, "Title", 2, 150);
        String d = Validation.optional(description, "Description", 2000);
        Tx.inTransaction(db, con -> {
            Material m = materials.lockById(con, materialId).orElseThrow(() -> new NotFoundException("File not found."));
            boolean owner = m.uploaderId() == u.id() && m.kind() != MaterialKind.NOTE;
            if (!owner) {
                access.require(con, u, m.courseId(), CoursePermission.ORGANIZE_RESOURCES);
            }
            if (lectureId != null && lectures.visibleLecture(con, u, lectureId).courseId() != m.courseId()) {
                throw new ValidationException("The lecture belongs to a different course.");
            }
            materials.updateDetails(con, materialId, t, d, lectureId, unitNo);
            audit.log(con, u.id(), "MATERIAL_UPDATE", "material", materialId, "");
            return null;
        });
    }

    /** Archiving hides a file but keeps its record and bytes (it may be referenced elsewhere). */
    public void archive(User u, long materialId) throws SQLException {
        Tx.inTransaction(db, con -> {
            Material m = materials.lockById(con, materialId).orElseThrow(() -> new NotFoundException("File not found."));
            boolean ownNote = m.kind() == MaterialKind.NOTE && m.uploaderId() == u.id();
            if (!ownNote) {
                access.require(con, u, m.courseId(), CoursePermission.ORGANIZE_RESOURCES);
            }
            materials.archive(con, materialId);
            audit.log(con, u.id(), "MATERIAL_ARCHIVE", "material", materialId, "");
            return null;
        });
    }

    /** Tells enrolled students about a newly published file (once per file, thanks to the dedupe key). */
    private void notifyPublished(Connection con, User u, long courseId, long materialId, String title) throws SQLException {
        String code = new com.classsync.dao.CourseDao().findById(con, courseId).orElseThrow().code();
        notifications.notifyCourseLearners(con, courseId, u.id(), new NotificationDao.Message("MATERIAL",
                "New material in " + code + ": " + title, "", "MATERIAL", materialId, "material-published:" + materialId));
    }

    /** Professor recommendation flag (shown in the library and notes ranking). */
    public void setRecommended(User u, long materialId, boolean recommended) throws SQLException {
        Tx.inTransaction(db, con -> {
            Material m = materials.lockById(con, materialId).orElseThrow(() -> new NotFoundException("File not found."));
            if (u.role() != Role.PROFESSOR) {
                throw new AccessDeniedException("Only professors can recommend resources.");
            }
            access.requireCourseProfessor(con, u, m.courseId());
            materials.setRecommended(con, materialId, recommended);
            return null;
        });
    }
}
