package com.classsync.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;

/** Database time. Rules that depend on "now" use this, never the desktop clock. */
public final class DbTime {

    private DbTime() {
    }

    /** clock_timestamp() is the real current time, even inside a long transaction. */
    public static Instant now(Connection con) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT clock_timestamp()"); ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getObject(1, OffsetDateTime.class).toInstant();
        }
    }
}
