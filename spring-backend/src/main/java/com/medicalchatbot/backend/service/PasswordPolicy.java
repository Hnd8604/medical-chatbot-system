package com.medicalchatbot.backend.service;

import java.nio.charset.StandardCharsets;

import com.medicalchatbot.backend.exception.AppException;
import com.medicalchatbot.backend.exception.ErrorCode;

/**
 * Luật mật khẩu dùng chung cho đăng ký và đặt lại mật khẩu, tránh lặp code.
 *
 * <p>BCrypt chỉ xử lý tối đa 72 byte nên mật khẩu bị chặn trên ở đó.</p>
 */
public final class PasswordPolicy {

    private PasswordPolicy() {
    }

    /**
     * Kiểm tra một mật khẩu mới. Ném {@link AppException} nếu không hợp lệ.
     */
    public static void validate(String password) {
        int passwordBytes = password.getBytes(StandardCharsets.UTF_8).length;
        if (passwordBytes < 8 || passwordBytes > 72) {
            throw new AppException(ErrorCode.INVALID_ARGUMENT, "Mật khẩu phải có từ 8 đến 72 byte.");
        }
        if (!password.matches(".*\\p{L}.*") || !password.matches(".*\\d.*")) {
            throw new AppException(ErrorCode.INVALID_ARGUMENT, "Mật khẩu phải có ít nhất một chữ và một số.");
        }
    }

    /**
     * Kiểm tra mật khẩu mới kèm xác nhận khớp nhau.
     */
    public static void validate(String password, String confirmation) {
        validate(password);
        if (!password.equals(confirmation)) {
            throw new AppException(ErrorCode.INVALID_ARGUMENT, "Xác nhận mật khẩu không khớp.");
        }
    }
}
