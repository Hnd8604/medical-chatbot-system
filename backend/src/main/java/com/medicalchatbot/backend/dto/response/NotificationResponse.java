package com.medicalchatbot.backend.dto.response;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.medicalchatbot.backend.enums.NotificationType;

public record NotificationResponse(
        UUID id,
        NotificationType type,
        String title,
        String content,
        @JsonProperty("is_read") boolean isRead,
        OffsetDateTime createdAt
) {
}
