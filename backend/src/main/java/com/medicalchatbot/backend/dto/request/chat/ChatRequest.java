package com.medicalchatbot.backend.dto.request.chat;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** One chat turn; omit {@code sessionId} to start a new conversation. */
public record ChatRequest(
        UUID sessionId,

        @Size(max = 100, message = "Mã bệnh nhân không được vượt quá {max} ký tự.")
        String patientId,

        @NotBlank(message = "Vui lòng nhập câu hỏi.")
        @Size(max = 8000, message = "Câu hỏi không được vượt quá {max} ký tự.")
        String message
) {
}
