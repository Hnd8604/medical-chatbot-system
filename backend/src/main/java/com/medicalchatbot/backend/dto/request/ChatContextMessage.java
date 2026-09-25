package com.medicalchatbot.backend.dto.request;

public record ChatContextMessage(
        String role,

        String content
) {
}
