package com.classsync.config;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TimeZone;
import java.util.regex.Pattern;

/**
 * Application settings.
 *
 * Precedence (highest first):
 *   1. Operating-system environment variables (e.g. CLASSSYNC_DB_URL)
 *   2. ./application.properties   (optional, ignored by Git)
 *   3. ./.env                     (optional, ignored by Git; the same file Docker Compose reads)
 *   4. Built-in defaults below
 *
 * Note: running Java on the host does NOT receive .env values from Docker Compose
 * automatically, which is why this class reads the file itself as a fallback.
 */
public class AppConfig {

    public static final String DB_URL = "CLASSSYNC_DB_URL";
    public static final String TEST_DB_URL = "CLASSSYNC_TEST_DB_URL";
    public static final String APP_USER = "CLASSSYNC_APP_DB_USER";
    public static final String APP_PASSWORD = "CLASSSYNC_APP_DB_PASSWORD";
    public static final String MIGRATOR_USER = "CLASSSYNC_MIGRATOR_USER";
    public static final String MIGRATOR_PASSWORD = "CLASSSYNC_MIGRATOR_PASSWORD";
    public static final String TIMEZONE = "CLASSSYNC_TIMEZONE";
    public static final String UPLOAD_DIR = "CLASSSYNC_UPLOAD_DIR";
    public static final String EMAIL_PATTERN = "CLASSSYNC_EMAIL_PATTERN";
    public static final String MAX_UPLOAD_MB = "CLASSSYNC_MAX_UPLOAD_MB";
    public static final String ATTENDANCE_CODE_SECONDS = "CLASSSYNC_ATTENDANCE_CODE_SECONDS";
    public static final String MIGRATIONS_DIR = "CLASSSYNC_MIGRATIONS_DIR";

    private static final List<String> KNOWN_KEYS = List.of(DB_URL, TEST_DB_URL, APP_USER, APP_PASSWORD,
            MIGRATOR_USER, MIGRATOR_PASSWORD, TIMEZONE, UPLOAD_DIR, EMAIL_PATTERN, MAX_UPLOAD_MB,
            ATTENDANCE_CODE_SECONDS, MIGRATIONS_DIR);

    private static final Map<String, String> DEFAULTS = Map.of(
            DB_URL, "jdbc:postgresql://localhost:5432/classsync",
            TEST_DB_URL, "jdbc:postgresql://localhost:5432/classsync_test",
            APP_USER, "classsync_app",
            MIGRATOR_USER, "classsync_migrator",
            TIMEZONE, "Asia/Kolkata",
            UPLOAD_DIR, "uploads",
            EMAIL_PATTERN, "^[a-z0-9._-]+@college\\.edu$",
            MAX_UPLOAD_MB, "20",
            ATTENDANCE_CODE_SECONDS, "120",
            MIGRATIONS_DIR, "database/migrations");

    private final Map<String, String> values;

    public AppConfig(Map<String, String> values) {
        this.values = Map.copyOf(values);
    }

    /** Loads configuration from the sources listed in the class comment. */
    public static AppConfig load() {
        Map<String, String> merged = new HashMap<>(DEFAULTS);
        merged.putAll(readKeyValueFile(Path.of(".env")));
        merged.putAll(readKeyValueFile(Path.of("application.properties")));
        for (String key : KNOWN_KEYS) {
            String env = System.getenv(key);
            if (env != null && !env.isBlank()) {
                merged.put(key, env);
            }
        }
        AppConfig config = new AppConfig(merged);
        // The PostgreSQL JDBC driver sends the JVM default time zone to the server. Windows
        // may report legacy names such as "Asia/Calcutta" that current PostgreSQL images reject,
        // so the JVM default is set to the configured academic time zone (e.g. "Asia/Kolkata").
        TimeZone.setDefault(TimeZone.getTimeZone(config.zone()));
        return config;
    }

    /** Reads KEY=VALUE lines (works for both .env and .properties files). */
    static Map<String, String> readKeyValueFile(Path file) {
        Map<String, String> result = new HashMap<>();
        if (!Files.isRegularFile(file)) {
            return result;
        }
        // .env files are not Java properties files: backslashes must be kept literally,
        // so we parse lines ourselves instead of using Properties.load for .env.
        try {
            if (file.getFileName().toString().endsWith(".properties")) {
                Properties p = new Properties();
                try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    p.load(r);
                }
                p.forEach((k, v) -> result.put(k.toString().trim(), v.toString().trim()));
            } else {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    String t = line.trim();
                    int eq = t.indexOf('=');
                    if (t.isEmpty() || t.startsWith("#") || eq <= 0) {
                        continue;
                    }
                    String value = t.substring(eq + 1).trim();
                    if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"")
                            || value.startsWith("'") && value.endsWith("'"))) {
                        value = value.substring(1, value.length() - 1);
                    }
                    result.put(t.substring(0, eq).trim(), value);
                }
            }
        } catch (IOException e) {
            System.err.println("Warning: could not read " + file + ": " + e.getMessage());
        }
        return result;
    }

    public String get(String key) {
        return values.get(key);
    }

    public String require(String key) {
        String v = values.get(key);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("Missing configuration value " + key
                    + ". Set it as an environment variable or in .env (see .env.example).");
        }
        return v;
    }

    public int getInt(String key) {
        return Integer.parseInt(require(key).trim());
    }

    public ZoneId zone() {
        return ZoneId.of(require(TIMEZONE));
    }

    public Pattern emailPattern() {
        return Pattern.compile(require(EMAIL_PATTERN));
    }

    public Path uploadRoot() {
        return Path.of(require(UPLOAD_DIR)).toAbsolutePath().normalize();
    }

    public long maxUploadBytes() {
        return getInt(MAX_UPLOAD_MB) * 1024L * 1024L;
    }

    /** Copy with one value replaced (used by tests). */
    public AppConfig with(String key, String value) {
        Map<String, String> copy = new HashMap<>(values);
        copy.put(key, value);
        return new AppConfig(copy);
    }
}
