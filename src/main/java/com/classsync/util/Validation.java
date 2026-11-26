package com.classsync.util;

import com.classsync.service.AppException.ValidationException;

/** Small reusable checks. Each throws ValidationException with a message for the user. */
public final class Validation {

    private Validation() {
    }

    /** Trims and checks length. Returns the trimmed text. */
    public static String text(String value, String field, int min, int max) {
        String v = value == null ? "" : value.trim();
        if (v.length() < min) {
            throw new ValidationException(min <= 1 ? field + " is required." : field + " must be at least " + min + " characters.");
        }
        if (v.length() > max) {
            throw new ValidationException(field + " must be at most " + max + " characters.");
        }
        return v;
    }

    /** Optional text: blank becomes "", otherwise limited to max characters. */
    public static String optional(String value, String field, int max) {
        return text(value == null ? "" : value, field, 0, max);
    }

    public static int range(int value, String field, int min, int max) {
        if (value < min || value > max) {
            throw new ValidationException(field + " must be between " + min + " and " + max + ".");
        }
        return value;
    }

    /** Password policy: 8–128 characters with at least one letter and one digit. */
    public static void password(char[] password) {
        if (password == null || password.length < 8) {
            throw new ValidationException("Password must be at least 8 characters.");
        }
        if (password.length > 128) {
            throw new ValidationException("Password must be at most 128 characters.");
        }
        boolean letter = false;
        boolean digit = false;
        for (char c : password) {
            letter |= Character.isLetter(c);
            digit |= Character.isDigit(c);
        }
        if (!letter || !digit) {
            throw new ValidationException("Password must contain at least one letter and one digit.");
        }
    }
}
