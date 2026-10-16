package com.medicalchatbot.backend.util;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import com.medicalchatbot.backend.exception.AppException;
import com.medicalchatbot.backend.exception.ErrorCode;

/**
 * Password rules shared by registration, password change and password reset.
 *
 * <p>BCrypt only uses the first 72 bytes, so longer passwords are rejected instead of being
 * silently truncated.</p>
 */
public final class PasswordPolicy {

    private static final int MIN_BYTES = 8;
    private static final int MAX_BYTES = 72;
    private static final Pattern HAS_LETTER = Pattern.compile("\\p{L}");
    private static final Pattern HAS_DIGIT = Pattern.compile("\\d");

    private PasswordPolicy() {
    }

    /** Validates a new password and its confirmation; throws {@link AppException} when invalid. */
    public static void validate(String password, String confirmation) {
        int passwordBytes = password.getBytes(StandardCharsets.UTF_8).length;
        if (passwordBytes < MIN_BYTES || passwordBytes > MAX_BYTES) {
            throw new AppException(ErrorCode.PASSWORD_LENGTH_INVALID);
        }
        if (!HAS_LETTER.matcher(password).find() || !HAS_DIGIT.matcher(password).find()) {
            throw new AppException(ErrorCode.PASSWORD_TOO_WEAK);
        }
        if (!password.equals(confirmation)) {
            throw new AppException(ErrorCode.PASSWORD_CONFIRMATION_MISMATCH);
        }
    }
}
