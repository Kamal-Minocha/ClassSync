package com.classsync.config;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/** The schema version this build of the application expects (highest migration number). */
public final class SchemaVersion {

    public static final int REQUIRED = 7;

    private SchemaVersion() {
    }

    /** Reads the applied version; 0 if the database has never been migrated. */
    public static int current(Connection con) throws SQLException {
        try (Statement st = con.createStatement();
             var rs = st.executeQuery("SELECT to_regclass('public.schema_migrations') IS NOT NULL")) {
            rs.next();
            if (!rs.getBoolean(1)) {
                return 0;
            }
        }
        try (Statement st = con.createStatement();
             var rs = st.executeQuery("SELECT coalesce(max(version), 0) FROM schema_migrations")) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
