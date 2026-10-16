package com.medicalchatbot.backend.dto.request;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** One chat turn; omit {@code sessionId} to start a new conversation. */
public record ChatRequest(
        UUID sessionId,
        @Size(max = 100) String patientId,
        @NotBlank @Size(max = 8000) String message
) {
}
