package com.classsync.config;

import com.classsync.tools.Migrate;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SchemaVersionTest {

    @Test
    void requiredVersionMatchesNewestMigrationFile() throws Exception {
        assertEquals(Migrate.loadMigrations(Path.of("database/migrations")).size(), SchemaVersion.REQUIRED,
                "Bump SchemaVersion.REQUIRED when adding a migration");
    }
}
