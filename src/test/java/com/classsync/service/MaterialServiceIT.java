package com.classsync.service;

import com.classsync.Fixtures;
import com.classsync.TestDb;
import com.classsync.config.Database;
import com.classsync.model.CoursePermission;
import com.classsync.model.LectureStatus;
import com.classsync.model.Material;
import com.classsync.model.MaterialKind;
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
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MaterialServiceIT {

    @TempDir Path tmp;
    private Fixtures fx;
    private MaterialService service;
    private Path uploads;
    private User prof;
    private User student;
    private User outsider;
    private User cr;
    private long courseId;
    private long lectureId;

    @BeforeEach
    void setUp() throws Exception {
        Database db = TestDb.reset();
        fx = new Fixtures(db);
        AccessPolicy access = new AccessPolicy();
        uploads = tmp.resolve("uploads");
        LectureService lectures = new LectureService(db, access, Clock.system(ZoneId.of("Asia/Kolkata")));
        service = new MaterialService(db, access, new FileStorage(uploads, 1024 * 1024), lectures);
        prof = fx.professor("Prof");
        student = fx.student("Student");
        outsider = fx.student("Outsider");
        cr = fx.cr("CR");
        courseId = fx.course(prof, "CS301");
        fx.enroll(courseId, student, cr);
        lectureId = fx.lecture(courseId, prof, 1, 24, LectureStatus.PUBLISHED);
    }

    private Path pdf(String name) throws Exception {
        Path p = tmp.resolve(name);
        Files.writeString(p, "%PDF-1.4\n% test file\n");
        return p;
    }

    private MaterialService.UploadForm form(Path file, boolean publish) {
        return new MaterialService.UploadForm(courseId, lectureId, MaterialKind.MATERIAL, "Slides", "intro", 1, file, publish);
    }

    @Test
    void professorUploadIsStoredUnderManagedNameAndEnrolledStudentDownloadsIt() throws Exception {
        long id = service.upload(prof, form(pdf("week 1 slides.pdf"), true));
        Material m = service.get(student, id);
        assertEquals("week 1 slides.pdf", m.originalFilename(), "display name kept");
        assertTrue(m.storedPath().matches("materials/[0-9a-f-]{36}\\.pdf"), "generated relative path");
        assertTrue(Files.exists(uploads.resolve(m.storedPath())));

        Path dest = tmp.resolve("download.pdf");
        service.download(student, id, dest);
        assertEquals(Files.readString(pdf("again.pdf")), Files.readString(dest));
        assertEquals(1, service.get(prof, id).downloadCount());
    }

    @Test
    void outsidersCannotListOpenOrDownload() throws Exception {
        long id = service.upload(prof, form(pdf("a.pdf"), true));
        assertThrows(NotFoundException.class, () -> service.get(outsider, id));
        assertThrows(NotFoundException.class, () -> service.download(outsider, id, tmp.resolve("x.pdf")));
        assertThrows(NotFoundException.class, () -> service.pathForOpening(outsider, id));
        assertThrows(AccessDeniedException.class, () -> service.list(outsider,
                new MaterialService.Query(courseId, null, null, null, "newest", 50)));
        assertTrue(service.list(outsider, new MaterialService.Query(null, null, null, null, "newest", 50)).isEmpty());
    }

    @Test
    void unpublishedMaterialIsHiddenFromStudents() throws Exception {
        long id = service.upload(prof, form(pdf("draft.pdf"), false));
        assertThrows(NotFoundException.class, () -> service.get(student, id));
        assertTrue(service.list(student, new MaterialService.Query(courseId, null, null, null, "newest", 50)).isEmpty());
        service.setPublished(prof, id, true);
        assertEquals(1, service.list(student, new MaterialService.Query(courseId, null, null, null, "newest", 50)).size());
    }

    @Test
    void crUploadsByDefaultButPublishesOnlyWithOrganizePermission() throws Exception {
        assertThrows(AccessDeniedException.class, () -> service.upload(cr, form(pdf("a.pdf"), true)), "not staff yet");
        fx.staff(courseId, cr, prof, Set.of());
        long id = service.upload(cr, form(pdf("b.pdf"), true));
        assertFalse(service.get(cr, id).published(), "CR without ORGANIZE cannot publish");
        assertThrows(AccessDeniedException.class, () -> service.setPublished(cr, id, true));

        fx.staff(courseId, cr, prof, Set.of(CoursePermission.ORGANIZE_RESOURCES));
        long id2 = service.upload(cr, form(pdf("c.pdf"), true));
        assertTrue(service.get(cr, id2).published());
    }

    @Test
    void studentsCannotUploadMaterials() throws Exception {
        assertThrows(AccessDeniedException.class, () -> service.upload(student, form(pdf("a.pdf"), true)));
        assertEquals(0, countFiles(), "no file is left behind after a refused upload");
    }

    @Test
    void unsupportedOversizedAndDisguisedFilesAreRejected() throws Exception {
        Path exe = tmp.resolve("virus.exe");
        Files.writeString(exe, "MZ...");
        assertThrows(ValidationException.class, () -> service.upload(prof, form(exe, true)));

        Path fake = tmp.resolve("fake.pdf");
        Files.writeString(fake, "not really a pdf");
        assertThrows(ValidationException.class, () -> service.upload(prof, form(fake, true)), "signature check");

        Path big = tmp.resolve("big.pdf");
        Files.write(big, new byte[2 * 1024 * 1024]);
        Files.writeString(big, "%PDF", java.nio.file.StandardOpenOption.WRITE);
        assertThrows(ValidationException.class, () -> service.upload(prof, form(big, true)), "size limit");
        assertEquals(0, countFiles());
    }

    @Test
    void failedDatabaseInsertRemovesCopiedFile() throws Exception {
        // lecture from another course violates the composite FK -> insert fails after the copy
        long otherCourse = fx.course(prof, "CS302");
        MaterialService.UploadForm bad = new MaterialService.UploadForm(otherCourse, lectureId, MaterialKind.MATERIAL,
                "Slides", "", 1, pdf("x.pdf"), true);
        assertThrows(ValidationException.class, () -> service.upload(prof, bad));
        assertEquals(0, countFiles());
    }

    @Test
    void sortIsWhitelistedAndSearchIsParameterised() throws Exception {
        service.upload(prof, form(pdf("a.pdf"), true));
        List<Material> list = service.list(student,
                new MaterialService.Query(courseId, null, null, "Slides", "title; DROP TABLE materials", 50));
        assertEquals(1, list.size(), "unknown sort key falls back to the default order");
        assertTrue(service.list(student, new MaterialService.Query(courseId, null, null, "' OR '1'='1", "newest", 50)).isEmpty());
    }

    private long countFiles() throws Exception {
        if (!Files.exists(uploads)) {
            return 0;
        }
        try (var s = Files.walk(uploads)) {
            return s.filter(Files::isRegularFile).count();
        }
    }
}
