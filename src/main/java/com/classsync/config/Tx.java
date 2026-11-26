package com.classsync.config;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Small transaction helper used by every service.
 *
 * The service opens ONE connection, turns auto-commit off, passes that same
 * connection to every DAO call, then commits — or rolls back if anything fails.
 */
public final class Tx {

    private Tx() {
    }

    @FunctionalInterface
    public interface SqlWork<T> {
        T run(Connection con) throws SQLException;
    }

    public static <T> T inTransaction(Database db, SqlWork<T> work) throws SQLException {
        try (Connection con = db.open()) {
            con.setAutoCommit(false);
            try {
                T result = work.run(con);
                con.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                rollbackQuietly(con, e);
                throw e;
            }
        }
    }

    /** Read-only work on an auto-commit connection. */
    public static <T> T read(Database db, SqlWork<T> work) throws SQLException {
        try (Connection con = db.open()) {
            return work.run(con);
        }
    }

    private static void rollbackQuietly(Connection con, Exception original) {
        try {
            con.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }
}
