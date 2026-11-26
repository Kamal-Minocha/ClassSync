package com.classsync.tools;

import com.classsync.config.AppConfig;
import com.classsync.config.Database;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Versioned schema migrations.
 *
 * Files: database/migrations/V001__name.sql, V002__name.sql, ...
 * Applied versions are recorded in schema_migrations together with a SHA-256 checksum,
 * so editing an already-applied file is detected instead of silently ignored.
 *
 * Usage (run as the migration owner, never as the application user):
 *   mvnw exec:java@migrate                      apply pending migrations to CLASSSYNC_DB_URL
 *   mvnw exec:java@migrate -Dexec.args=status   list applied/pending
 *   mvnw exec:java@migrate -Dexec.args=schema   regenerate database/schema.sql
 */
public final class Migrate {

    private static final Pattern FILE_NAME = Pattern.compile("V(\\d{3})__([a-z0-9_]+)\\.sql");
    private static final Pattern SAFE_ROLE = Pattern.compile("[a-z_][a-z0-9_]{0,62}");
    private static final long ADVISORY_LOCK_KEY = 720_031_001L;

    public record Migration(int version, String description, Path file, String sql, String checksum) {
    }

    private Migrate() {
    }

    public static void main(String[] args) throws Exception {
        AppConfig config = AppConfig.load();
        String command = args.length == 0 ? "migrate" : args[0];
        Path dir = Path.of(config.require(AppConfig.MIGRATIONS_DIR));

        if (command.equals("schema")) {
            Path out = Path.of("database", "schema.sql");
            Files.writeString(out, buildSchemaFile(loadMigrations(dir)), StandardCharsets.UTF_8);
            System.out.println("Wrote " + out.toAbsolutePath());
            return;
        }

        Database db = new Database(config.require(AppConfig.DB_URL),
                config.require(AppConfig.MIGRATOR_USER), config.require(AppConfig.MIGRATOR_PASSWORD));
        try (Connection con = db.open()) {
            if (command.equals("status")) {
                printStatus(con, loadMigrations(dir));
            } else if (command.equals("migrate")) {
                List<Integer> applied = migrate(con, dir, config.require(AppConfig.APP_USER));
                System.out.println(applied.isEmpty() ? "Schema is up to date."
                        : "Applied migrations: " + applied);
            } else {
                System.err.println("Unknown command: " + command + " (use migrate, status or schema)");
                System.exit(2);
            }
        } catch (SQLException e) {
            System.err.println("Migration failed: " + e.getMessage());
            System.exit(1);
        }
    }

    /** Reads and validates all migration files in version order. */
    public static List<Migration> loadMigrations(Path dir) throws IOException {
        List<Migration> list = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.sorted().toList()) {
                Matcher m = FILE_NAME.matcher(file.getFileName().toString());
                if (!m.matches()) {
                    continue;
                }
                String sql = Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n");
                list.add(new Migration(Integer.parseInt(m.group(1)), m.group(2), file, sql, sha256(sql)));
            }
        }
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).version() != i + 1) {
                throw new IllegalStateException("Migration versions must be contiguous from V001; found "
                        + list.get(i).file().getFileName());
            }
        }
        return list;
    }

    /**
     * Applies every pending migration, each in its own transaction, then grants the
     * restricted application role the privileges it needs.
     *
     * @return versions applied by this call
     */
    public static List<Integer> migrate(Connection con, Path dir, String appRole) throws SQLException {
        if (!SAFE_ROLE.matcher(appRole).matches()) {
            throw new IllegalArgumentException("Unsafe application role name: " + appRole);
        }
        List<Migration> migrations;
        try {
            migrations = loadMigrations(dir);
        } catch (IOException e) {
            throw new SQLException("Cannot read migrations from " + dir.toAbsolutePath(), e);
        }

        boolean oldAutoCommit = con.getAutoCommit();
        con.setAutoCommit(false);
        List<Integer> appliedNow = new ArrayList<>();
        try {
            try (Statement st = con.createStatement()) {
                st.execute("""
                        CREATE TABLE IF NOT EXISTS schema_migrations (
                            version     INT PRIMARY KEY,
                            description VARCHAR(100) NOT NULL,
                            checksum    CHAR(64) NOT NULL,
                            applied_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
                            applied_by  VARCHAR(63) NOT NULL DEFAULT current_user
                        )""");
            }
            con.commit();

            for (Migration m : migrations) {
                // transaction-scoped lock: two migrators cannot run the same version concurrently
                try (Statement st = con.createStatement()) {
                    st.execute("SELECT pg_advisory_xact_lock(" + ADVISORY_LOCK_KEY + ")");
                }
                String existing = appliedChecksums(con).get(m.version());
                if (existing != null) {
                    con.commit();
                    if (!existing.equals(m.checksum())) {
                        throw new SQLException("Migration V%03d was changed after it was applied. "
                                .formatted(m.version()) + "Create a new migration instead of editing it.");
                    }
                    continue;
                }
                try (Statement st = con.createStatement()) {
                    st.execute(m.sql());
                }
                try (PreparedStatement ps = con.prepareStatement(
                        "INSERT INTO schema_migrations (version, description, checksum) VALUES (?, ?, ?)")) {
                    ps.setInt(1, m.version());
                    ps.setString(2, m.description());
                    ps.setString(3, m.checksum());
                    ps.executeUpdate();
                }
                con.commit();
                appliedNow.add(m.version());
            }

            grantApplicationRole(con, appRole);
            con.commit();
        } catch (SQLException | RuntimeException e) {
            con.rollback();
            throw e;
        } finally {
            con.setAutoCommit(oldAutoCommit);
        }
        return appliedNow;
    }

    /** Least privilege: data access only, no DDL; audit tables are append-only for the app. */
    private static void grantApplicationRole(Connection con, String role) throws SQLException {
        boolean exists;
        try (PreparedStatement ps = con.prepareStatement("SELECT 1 FROM pg_roles WHERE rolname = ?")) {
            ps.setString(1, role);
            try (ResultSet rs = ps.executeQuery()) {
                exists = rs.next();
            }
        }
        if (!exists) {
            System.err.println("Note: role " + role + " does not exist; skipping grants.");
            return;
        }
        try (Statement st = con.createStatement()) {
            st.execute("GRANT USAGE ON SCHEMA public TO " + role);
            st.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO " + role);
            st.execute("GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO " + role);
            st.execute("REVOKE INSERT, UPDATE, DELETE ON schema_migrations FROM " + role);
            for (String appendOnly : List.of("audit_logs", "attendance_corrections", "material_approvals")) {
                if (tableExists(con, appendOnly)) {
                    st.execute("REVOKE UPDATE, DELETE ON " + appendOnly + " FROM " + role);
                }
            }
        }
    }

    private static boolean tableExists(Connection con, String table) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT to_regclass(?) IS NOT NULL")) {
            ps.setString(1, "public." + table);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        }
    }

    public static Map<Integer, String> appliedChecksums(Connection con) throws SQLException {
        Map<Integer, String> map = new HashMap<>();
        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("SELECT version, checksum FROM schema_migrations")) {
            while (rs.next()) {
                map.put(rs.getInt("version"), rs.getString("checksum"));
            }
        }
        return map;
    }

    /** Highest applied version, or 0 when the schema has never been migrated. */
    public static int currentVersion(Connection con) throws SQLException {
        if (!tableExists(con, "schema_migrations")) {
            return 0;
        }
        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("SELECT coalesce(max(version), 0) FROM schema_migrations")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static void printStatus(Connection con, List<Migration> migrations) throws SQLException {
        Map<Integer, String> applied = tableExists(con, "schema_migrations")
                ? appliedChecksums(con) : Map.of();
        for (Migration m : migrations) {
            String state = !applied.containsKey(m.version()) ? "PENDING"
                    : applied.get(m.version()).equals(m.checksum()) ? "applied" : "CHANGED AFTER APPLY";
            System.out.printf("V%03d %-30s %s%n", m.version(), m.description(), state);
        }
    }

    static String buildSchemaFile(List<Migration> migrations) {
        StringBuilder sb = new StringBuilder("""
                -- ClassSync full schema (GENERATED from database/migrations — do not edit by hand).
                -- Regenerate with: mvnw exec:java@migrate -Dexec.args=schema
                -- Fresh installs should use the migration tool so schema_migrations is populated.
                """);
        for (Migration m : migrations) {
            sb.append("\n-- ===== V%03d %s =====\n".formatted(m.version(), m.description()));
            sb.append(m.sql().strip()).append('\n');
        }
        return sb.toString();
    }

    static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
