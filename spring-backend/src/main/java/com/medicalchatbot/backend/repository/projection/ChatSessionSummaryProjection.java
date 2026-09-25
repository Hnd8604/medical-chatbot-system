package com.medicalchatbot.backend.repository.projection;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ChatSessionSummaryProjection(
        UUID id,
        String title,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        String activePatientId,
        int messageCount,
        String lastMessagePreview
) {
}
