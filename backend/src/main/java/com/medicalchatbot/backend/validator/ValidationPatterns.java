package com.medicalchatbot.backend.validator;

/** Regular expressions shared by request DTO constraints. */
public final class ValidationPatterns {

    /** 3-30 letters, digits, dots, underscores or hyphens; stored in lower case. */
    public static final String USERNAME = "^[a-zA-Z0-9._-]{3,30}$";

    /** Stricter than the default {@code @Email}: the domain must contain a dot. */
    public static final String EMAIL = "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$";

    private ValidationPatterns() {
    }
}
