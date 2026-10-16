package com.medicalchatbot.backend.dto.request.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChangePasswordRequest(
        @NotBlank(message = "Vui lòng nhập mật khẩu hiện tại.")
        @Size(max = 72, message = "Mật khẩu hiện tại không được vượt quá {max} ký tự.")
        String currentPassword,

        @NotBlank(message = "Vui lòng nhập mật khẩu mới.")
        @Size(min = 8, max = 72, message = "Mật khẩu mới phải có từ {min} đến {max} ký tự.")
        String newPassword,

        @NotBlank(message = "Vui lòng nhập lại mật khẩu mới.")
        @Size(min = 8, max = 72, message = "Mật khẩu nhập lại phải có từ {min} đến {max} ký tự.")
        String passwordConfirmation
) {
}
