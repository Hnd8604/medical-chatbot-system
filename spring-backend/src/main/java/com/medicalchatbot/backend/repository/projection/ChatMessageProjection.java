package com.medicalchatbot.backend.repository.projection;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ChatMessageProjection(
        UUID id,
        String role,
        String content,
        OffsetDateTime createdAt,
        Integer feedbackRating,
        String feedbackComment
) {
}
