package com.medicalchatbot.backend.dto.request.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResetPasswordRequest(
        @NotBlank(message = "Thiếu mã đặt lại mật khẩu.")
        @Size(max = 512, message = "Mã đặt lại mật khẩu không hợp lệ.")
        String resetTicket,

        @NotBlank(message = "Vui lòng nhập mật khẩu mới.")
        @Size(min = 8, max = 72, message = "Mật khẩu mới phải có từ {min} đến {max} ký tự.")
        String newPassword,

        @NotBlank(message = "Vui lòng nhập lại mật khẩu mới.")
        @Size(min = 8, max = 72, message = "Mật khẩu nhập lại phải có từ {min} đến {max} ký tự.")
        String passwordConfirmation
) {
}
