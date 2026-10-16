package com.medicalchatbot.backend.util;

import java.util.Locale;

/** Null-safe helpers for text received from clients or upstream services. */
public final class TextUtils {

    private TextUtils() {
    }

    /** Trims surrounding whitespace; {@code null} becomes an empty string. */
    public static String strip(String value) {
        return value == null ? "" : value.strip();
    }

    /** Trimmed value, or {@code null} when the value is missing or blank. */
    public static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        return stripped.isEmpty() ? null : stripped;
    }

    public static String normalizeEmail(String email) {
        return strip(email).toLowerCase(Locale.ROOT);
    }

    public static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    public static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
