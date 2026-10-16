package com.medicalchatbot.backend.dto.request.auth;

import com.medicalchatbot.backend.validator.ValidationPatterns;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateProfileRequest(
        @NotBlank(message = "Vui lòng nhập tên hiển thị.")
        @Size(min = 2, max = 100, message = "Tên hiển thị phải có từ {min} đến {max} ký tự.")
        String displayName,

        @NotBlank(message = "Vui lòng nhập email.")
        @Email(regexp = ValidationPatterns.EMAIL, message = "Email không hợp lệ.")
        @Size(max = 255, message = "Email không được vượt quá {max} ký tự.")
        String email
) {
}
