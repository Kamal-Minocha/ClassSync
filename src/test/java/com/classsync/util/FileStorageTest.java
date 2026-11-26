package com.classsync.util;

import com.classsync.service.AppException.ValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class FileStorageTest {

    @TempDir Path tmp;

    @Test
    void pathTraversalAndAbsolutePathsAreRejected() throws Exception {
        FileStorage fs = new FileStorage(tmp.resolve("uploads"), 1024);
        Files.createDirectories(tmp.resolve("uploads"));
        Files.writeString(tmp.resolve("secret.txt"), "secret");
        assertThrows(ValidationException.class, () -> fs.resolve("../secret.txt"));
        assertThrows(ValidationException.class, () -> fs.resolve("materials/../../secret.txt"));
        assertThrows(ValidationException.class, () -> fs.resolve("/etc/passwd"));
        assertThrows(ValidationException.class, () -> fs.resolve("C:\\Windows\\win.ini"));
        assertThrows(ValidationException.class, () -> fs.resolve(""));
    }

    @Test
    void missingFileGivesClearMessage() {
        FileStorage fs = new FileStorage(tmp, 1024);
        ValidationException e = assertThrows(ValidationException.class, () -> fs.resolve("materials/none.pdf"));
        assertTrue(e.getMessage().contains("missing"));
    }

    @Test
    void storeNeverOverwritesAndUsesGeneratedNames() throws Exception {
        FileStorage fs = new FileStorage(tmp.resolve("u"), 1024);
        Path src = tmp.resolve("same.png");
        Files.write(src, new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3});
        var a = fs.store(src, "materials");
        var b = fs.store(src, "materials");
        assertNotEquals(a.relativePath(), b.relativePath());
        assertEquals("png", a.fileType());
        assertTrue(Files.exists(fs.resolve(a.relativePath())));
    }

    @Test
    void displayNameIsSanitised() {
        assertEquals("evil.pdf", FileStorage.safeDisplayName("..\\..\\evil.pdf"));
        assertEquals("evil.pdf", FileStorage.safeDisplayName("/tmp/evil.pdf"));
        assertEquals("ab.pdf", FileStorage.safeDisplayName("a\u0000b.pdf"));
        assertTrue(FileStorage.safeDisplayName("x".repeat(300) + ".pdf").endsWith(".pdf"));
    }

    @Test
    void extensionCaseIsIgnored() {
        assertEquals("pdf", FileStorage.extension("Report.PDF"));
        assertEquals("", FileStorage.extension("README"));
    }
}
