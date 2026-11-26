package com.classsync.util;

import com.classsync.service.AppException.ValidationException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Managed uploads directory.
 *
 * - Only allow-listed extensions, a maximum size, and a matching file signature ("magic bytes").
 * - Stored names are generated (UUID); the original name is kept only as display text.
 * - Every path is resolved under the root and checked, so "../" or symbolic links cannot escape it.
 * - Only RELATIVE paths (e.g. "materials/3f2c....pdf") are stored in PostgreSQL.
 *
 * File operations are not part of JDBC transactions: services copy the file first, then insert
 * the row, and delete the copy if the insert fails.
 */
public class FileStorage {

    public record StoredFile(String relativePath, String fileType, long sizeBytes, String displayName) {
    }

    private static final HexFormat HEX = HexFormat.of();
    private static final byte[] PDF = HEX.parseHex("25504446");          // %PDF
    private static final byte[] PNG = HEX.parseHex("89504e470d0a1a0a");
    private static final byte[] JPG = HEX.parseHex("ffd8ff");
    private static final byte[] OLE = HEX.parseHex("d0cf11e0a1b11ae1");  // legacy .doc / .ppt
    private static final byte[] ZIP = HEX.parseHex("504b0304");          // .docx / .pptx (Office Open XML)

    public static final Map<String, byte[]> SIGNATURES = Map.of(
            "pdf", PDF, "png", PNG, "jpg", JPG, "jpeg", JPG,
            "doc", OLE, "ppt", OLE, "docx", ZIP, "pptx", ZIP);
    public static final List<String> ALLOWED = List.of("pdf", "ppt", "pptx", "doc", "docx", "jpg", "jpeg", "png");

    private final Path root;
    private final long maxBytes;

    public FileStorage(Path root, long maxBytes) {
        this.root = root.toAbsolutePath().normalize();
        this.maxBytes = maxBytes;
    }

    public Path root() {
        return root;
    }

    public long maxBytes() {
        return maxBytes;
    }

    /** Validates the source file and copies it into root/category under a generated name. */
    public StoredFile store(Path source, String category) throws IOException {
        if (!category.matches("[a-z]+")) {
            throw new IllegalArgumentException("bad category");
        }
        if (source == null || !Files.isRegularFile(source)) {
            throw new ValidationException("Choose an existing file.");
        }
        String display = safeDisplayName(source.getFileName().toString());
        String ext = extension(display);
        if (!ALLOWED.contains(ext)) {
            throw new ValidationException("Unsupported file type. Allowed: PDF, PPT/PPTX, DOC/DOCX, JPG/JPEG, PNG.");
        }
        long size = Files.size(source);
        if (size == 0) {
            throw new ValidationException("The file is empty.");
        }
        if (size > maxBytes) {
            throw new ValidationException("The file is larger than the " + (maxBytes / (1024 * 1024)) + " MB limit.");
        }
        if (!hasSignature(source, SIGNATURES.get(ext))) {
            throw new ValidationException("The file content does not look like a ." + ext + " file.");
        }

        Path dir = root.resolve(category);
        Files.createDirectories(dir);
        String storedName = UUID.randomUUID() + "." + ext;
        Path target = dir.resolve(storedName).normalize();
        if (!target.startsWith(root)) {
            throw new ValidationException("Invalid storage path.");
        }
        // Files.copy without REPLACE_EXISTING never overwrites an existing file
        Files.copy(source, target);
        return new StoredFile(category + "/" + storedName, ext, size, display);
    }

    /** Resolves a stored relative path, refusing anything outside the root (including via symlinks). */
    public Path resolve(String relativePath) throws IOException {
        if (relativePath == null || relativePath.isBlank() || relativePath.contains("..")
                || relativePath.startsWith("/") || relativePath.contains("\\") || relativePath.contains(":")) {
            throw new ValidationException("Invalid file reference.");
        }
        Path p = root.resolve(relativePath).normalize();
        if (!p.startsWith(root)) {
            throw new ValidationException("Invalid file reference.");
        }
        if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) {
            throw new ValidationException("The file is missing from the uploads folder. Ask the uploader to upload it again.");
        }
        if (Files.isSymbolicLink(p) || !p.toRealPath().startsWith(root.toRealPath())) {
            throw new ValidationException("Invalid file reference.");
        }
        return p;
    }

    public void deleteQuietly(String relativePath) {
        try {
            Path p = root.resolve(relativePath).normalize();
            if (p.startsWith(root)) {
                Files.deleteIfExists(p);
            }
        } catch (IOException | RuntimeException ignored) {
            // best effort cleanup
        }
    }

    /** Copies a stored file to a user-chosen destination (download / save-as). */
    public void exportTo(String relativePath, Path destination) throws IOException {
        Files.copy(resolve(relativePath), destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    public static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** Keeps only the last path element, removes control characters and limits length. */
    public static String safeDisplayName(String original) {
        String name = original.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        name = name.replaceAll("[\\p{Cntrl}]", "").trim();
        if (name.isEmpty()) {
            name = "file";
        }
        if (name.length() > 200) {
            String ext = extension(name);
            name = name.substring(0, 190) + (ext.isEmpty() ? "" : "." + ext);
        }
        return name;
    }

    private static boolean hasSignature(Path file, byte[] signature) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] head = in.readNBytes(signature.length);
            return Arrays.equals(head, signature);
        }
    }
}
