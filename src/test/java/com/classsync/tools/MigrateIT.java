package com.classsync.tools;

import com.classsync.TestDb;
import com.classsync.config.AppConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MigrateIT {

    @Test
    void rerunningMigrationsIsANoOp() throws Exception {
        var db = TestDb.reset();
        Path dir = Path.of(TestDb.config().require(AppConfig.MIGRATIONS_DIR));
        try (Connection con = db.open()) {
            int expected = Migrate.loadMigrations(dir).size();
            assertEquals(expected, Migrate.currentVersion(con));
            assertEquals(List.of(), Migrate.migrate(con, dir, "classsync_app"));
        }
    }

    @Test
    void editedAppliedMigrationIsDetected(@TempDir Path tmp) throws Exception {
        var db = TestDb.reset();
        Path dir = Path.of(TestDb.config().require(AppConfig.MIGRATIONS_DIR));
        for (Path f : Migrate.loadMigrations(dir).stream().map(Migrate.Migration::file).toList()) {
            Files.copy(f, tmp.resolve(f.getFileName()));
        }
        Path first = tmp.resolve("V001__accounts_courses.sql");
        Files.writeString(first, Files.readString(first) + "\n-- edited\n");
        try (Connection con = db.open()) {
            SQLException e = assertThrows(SQLException.class, () -> Migrate.migrate(con, tmp, "classsync_app"));
            assertTrue(e.getMessage().contains("changed after it was applied"));
        }
    }

    @Test
    void newMigrationIsAppliedWithoutResettingData(@TempDir Path tmp) throws Exception {
        var db = TestDb.reset();
        Path dir = Path.of(TestDb.config().require(AppConfig.MIGRATIONS_DIR));
        List<Migrate.Migration> existing = Migrate.loadMigrations(dir);
        for (Migrate.Migration m : existing) {
            Files.copy(m.file(), tmp.resolve(m.file().getFileName()));
        }
        try (Connection con = db.open(); var st = con.createStatement()) {
            st.execute("INSERT INTO users (email, full_name, role, password_hash) VALUES ('keep@college.edu','Keep','STUDENT','h')");
            int next = existing.size() + 1;
            Files.writeString(tmp.resolve("V%03d__add_probe.sql".formatted(next)),
                    "CREATE TABLE probe (id INT PRIMARY KEY);");
            assertEquals(List.of(next), Migrate.migrate(con, tmp, "classsync_app"));
            try (var rs = st.executeQuery("SELECT count(*) FROM users WHERE email = 'keep@college.edu'")) {
                rs.next();
                assertEquals(1, rs.getInt(1), "existing data survives an upgrade");
            }
        }
    }

    @Test
    void applicationRoleCannotChangeSchemaOrAuditLog() throws Exception {
        TestDb.reset();
        AppConfig cfg = TestDb.config();
        String appPassword = cfg.get(AppConfig.APP_PASSWORD);
        org.junit.jupiter.api.Assumptions.assumeTrue(appPassword != null, "app password not configured");
        var appDb = new com.classsync.config.Database(cfg.require(AppConfig.TEST_DB_URL),
                cfg.require(AppConfig.APP_USER), appPassword);
        try (Connection con = appDb.open(); var st = con.createStatement()) {
            st.executeQuery("SELECT count(*) FROM users").close();
            SQLException ddl = assertThrows(SQLException.class, () -> st.execute("CREATE TABLE hack (id INT)"));
            assertEquals("42501", ddl.getSQLState(), "insufficient_privilege");
            SQLException audit = assertThrows(SQLException.class, () -> st.execute("DELETE FROM audit_logs"));
            assertEquals("42501", audit.getSQLState());
        }
    }
}
