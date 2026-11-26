package com.classsync.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AppConfigTest {

    @Test
    void envFileKeepsBackslashesAndStripsQuotesAndComments(@TempDir Path dir) throws Exception {
        Path env = dir.resolve(".env");
        Files.writeString(env, """
                # comment
                CLASSSYNC_EMAIL_PATTERN=^[a-z]+@college\\.edu$
                CLASSSYNC_APP_DB_PASSWORD="quoted value"

                NOT_A_PAIR
                """);
        Map<String, String> values = AppConfig.readKeyValueFile(env);
        assertEquals("^[a-z]+@college\\.edu$", values.get("CLASSSYNC_EMAIL_PATTERN"));
        assertEquals("quoted value", values.get("CLASSSYNC_APP_DB_PASSWORD"));
        assertFalse(values.containsKey("NOT_A_PAIR"));
    }

    @Test
    void missingRequiredValueGivesHelpfulMessage() {
        AppConfig cfg = new AppConfig(Map.of());
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> cfg.require(AppConfig.APP_PASSWORD));
        assertTrue(e.getMessage().contains(".env.example"));
    }

    @Test
    void derivedValues() {
        AppConfig cfg = new AppConfig(Map.of(AppConfig.MAX_UPLOAD_MB, "2",
                AppConfig.TIMEZONE, "Asia/Kolkata",
                AppConfig.EMAIL_PATTERN, "^[a-z]+@college\\.edu$"));
        assertEquals(2L * 1024 * 1024, cfg.maxUploadBytes());
        assertEquals("Asia/Kolkata", cfg.zone().getId());
        assertTrue(cfg.emailPattern().matcher("asha@college.edu").matches());
        assertFalse(cfg.emailPattern().matcher("asha@gmail.com").matches());
    }

    @Test
    void missingFileYieldsEmptyMap(@TempDir Path dir) {
        assertTrue(AppConfig.readKeyValueFile(dir.resolve("nope.env")).isEmpty());
    }
}
