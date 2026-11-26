package com.classsync.service;

/**
 * Base class for expected, user-facing failures. The message is safe to show in the UI.
 * Subclasses tell the UI what kind of problem happened.
 */
public class AppException extends RuntimeException {
    public AppException(String message) {
        super(message);
    }

    /** Input is invalid (empty field, wrong format, duplicate, rule violated). */
    public static class ValidationException extends AppException {
        public ValidationException(String message) {
            super(message);
        }
    }

    /** The signed-in user is not allowed to do this. */
    public static class AccessDeniedException extends AppException {
        public AccessDeniedException(String message) {
            super(message);
        }
    }

    /** The record does not exist (or the user may not know it exists). */
    public static class NotFoundException extends AppException {
        public NotFoundException(String message) {
            super(message);
        }
    }

    /** Login failed. Deliberately does not say whether the account exists. */
    public static class AuthenticationException extends AppException {
        public AuthenticationException(String message) {
            super(message);
        }
    }
}
