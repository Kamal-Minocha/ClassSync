package com.classsync;

import com.classsync.config.AppConfig;
import com.classsync.config.Database;
import com.classsync.tools.Migrate;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Integration-test database helper.
 *
 * Connects to CLASSSYNC_TEST_DB_URL as the migration owner. reset() DROPS and recreates
 * the public schema, so it refuses to run unless the database name ends in "_test".
 * The normal classsync database is never touched by tests.
 */
public final class TestDb {

    private static final AppConfig CONFIG = AppConfig.load();
    private static final Database DB = new Database(
            CONFIG.require(AppConfig.TEST_DB_URL),
            CONFIG.require(AppConfig.MIGRATOR_USER),
            CONFIG.require(AppConfig.MIGRATOR_PASSWORD));

    private TestDb() {
    }

    public static AppConfig config() {
        return CONFIG;
    }

    public static Database database() {
        return DB;
    }

    /** Fresh, fully migrated schema. */
    public static Database reset() {
        try (Connection con = DB.open(); Statement st = con.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT current_database()")) {
                rs.next();
                String name = rs.getString(1);
                if (!name.endsWith("_test")) {
                    throw new IllegalStateException("Refusing to reset non-test database " + name);
                }
            }
            st.execute("DROP SCHEMA IF EXISTS public CASCADE");
            st.execute("CREATE SCHEMA public");
            Migrate.migrate(con, Path.of(CONFIG.require(AppConfig.MIGRATIONS_DIR)),
                    CONFIG.require(AppConfig.APP_USER));
            return DB;
        } catch (SQLException e) {
            throw new IllegalStateException("Test database unavailable at " + DB.describe()
                    + " — start it with: docker compose up -d postgres. Cause: " + e.getMessage(), e);
        }
    }
}
