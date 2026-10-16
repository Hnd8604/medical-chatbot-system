package com.medicalchatbot.backend.dto.request.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank(message = "Vui lòng nhập tên đăng nhập hoặc email.")
        @Size(max = 255, message = "Tên đăng nhập hoặc email không được vượt quá {max} ký tự.")
        String usernameOrEmail,

        @NotBlank(message = "Vui lòng nhập mật khẩu.")
        @Size(max = 72, message = "Mật khẩu không được vượt quá {max} ký tự.")
        String password
) {
}
