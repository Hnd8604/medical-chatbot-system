package com.medicalchatbot.backend.dto.request.auth;

import com.medicalchatbot.backend.validator.ValidationPatterns;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record VerifyResetCodeRequest(
        @NotBlank(message = "Vui lòng nhập email.")
        @Email(regexp = ValidationPatterns.EMAIL, message = "Email không hợp lệ.")
        @Size(max = 255, message = "Email không được vượt quá {max} ký tự.")
        String email,

        @NotBlank(message = "Vui lòng nhập mã xác thực.")
        @Pattern(regexp = "^\\d{6}$", message = "Mã xác thực gồm 6 chữ số.")
        String code
) {
}
