package com.medicalchatbot.backend.service;

import java.nio.charset.StandardCharsets;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Luật mật khẩu dùng chung cho đăng ký và đặt lại mật khẩu, tránh lặp code.
 *
 * <p>BCrypt chỉ xử lý tối đa 72 byte nên mật khẩu bị chặn trên ở đó.</p>
 */
public final class PasswordPolicy {

    private PasswordPolicy() {
    }

    /**
     * Kiểm tra một mật khẩu mới. Ném {@link ResponseStatusException} 400 nếu không hợp lệ.
     */
    public static void validate(String password) {
        int passwordBytes = password.getBytes(StandardCharsets.UTF_8).length;
        if (passwordBytes < 8 || passwordBytes > 72) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mật khẩu phải có từ 8 đến 72 byte.");
        }
        if (!password.matches(".*\\p{L}.*") || !password.matches(".*\\d.*")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mật khẩu phải có ít nhất một chữ và một số.");
        }
    }

    /**
     * Kiểm tra mật khẩu mới kèm xác nhận khớp nhau.
     */
    public static void validate(String password, String confirmation) {
        validate(password);
        if (!password.equals(confirmation)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Xác nhận mật khẩu không khớp.");
        }
    }
}
