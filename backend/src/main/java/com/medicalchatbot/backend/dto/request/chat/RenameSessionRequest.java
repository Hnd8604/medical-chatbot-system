package com.medicalchatbot.backend.dto.request.chat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RenameSessionRequest(
        @NotBlank(message = "Tiêu đề hội thoại không được để trống.")
        @Size(max = 255, message = "Tiêu đề hội thoại không được vượt quá {max} ký tự.")
        String title
) {
}
