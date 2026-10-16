package com.medicalchatbot.backend.dto.request.auth;

import com.medicalchatbot.backend.validator.ValidationPatterns;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** The username is stored in lower case; password strength is checked by {@code PasswordPolicy}. */
public record RegisterRequest(
        @NotBlank(message = "Vui lòng nhập tên hiển thị.")
        @Size(min = 2, max = 100, message = "Tên hiển thị phải có từ {min} đến {max} ký tự.")
        String displayName,

        @NotBlank(message = "Vui lòng nhập tên đăng nhập.")
        @Pattern(regexp = ValidationPatterns.USERNAME,
                message = "Tên đăng nhập phải có 3-30 ký tự và chỉ gồm chữ, số, dấu chấm, gạch dưới hoặc gạch ngang.")
        String username,

        @NotBlank(message = "Vui lòng nhập email.")
        @Email(regexp = ValidationPatterns.EMAIL, message = "Email không hợp lệ.")
        @Size(max = 255, message = "Email không được vượt quá {max} ký tự.")
        String email,

        @NotBlank(message = "Vui lòng nhập mật khẩu.")
        @Size(min = 8, max = 72, message = "Mật khẩu phải có từ {min} đến {max} ký tự.")
        String password,

        @NotBlank(message = "Vui lòng nhập lại mật khẩu.")
        @Size(min = 8, max = 72, message = "Mật khẩu nhập lại phải có từ {min} đến {max} ký tự.")
        String passwordConfirmation
) {
}
