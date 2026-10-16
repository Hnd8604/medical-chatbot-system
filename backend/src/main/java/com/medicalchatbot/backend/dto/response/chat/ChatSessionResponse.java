package com.medicalchatbot.backend.dto.response.chat;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ChatSessionResponse(
        UUID id,
        String title,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        String activePatientId,
        int messageCount,
        String lastMessagePreview
) {
}
