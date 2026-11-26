package com.classsync.service;

import java.sql.SQLException;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Turns exceptions into messages that are safe and useful to show to users. */
public final class Errors {

    private static final Logger LOG = Logger.getLogger("classsync");

    private Errors() {
    }

    public static String userMessage(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && !(root instanceof AppException) && !(root instanceof SQLException)) {
            root = root.getCause();
        }
        if (root instanceof AppException) {
            return root.getMessage();
        }
        if (root instanceof SQLException sql) {
            return sqlMessage(sql);
        }
        LOG.log(Level.WARNING, "Unexpected error", t);
        return "Something went wrong. Please try again.";
    }

    /** Maps PostgreSQL SQLSTATE codes to friendly text. Credentials are never included. */
    public static String sqlMessage(SQLException e) {
        String state = e.getSQLState() == null ? "" : e.getSQLState();
        LOG.log(Level.WARNING, "Database error " + state + ": " + e.getMessage());
        if (state.startsWith("08")) {
            return "Cannot reach the database. Check that Docker is running (docker compose up -d postgres).";
        }
        if (state.equals("28P01") || state.equals("28000")) {
            return "The database rejected the application's credentials. Check your .env configuration.";
        }
        return switch (state) {
            case "23505" -> "This record already exists.";
            case "23503" -> "This record is linked to other data and cannot be changed that way.";
            case "23514" -> "A value is outside the allowed range.";
            case "40001", "40P01" -> "Another user changed this at the same time. Please try again.";
            case "42501" -> "The database denied this operation.";
            default -> "A database error occurred. Please try again.";
        };
    }
}
