package com.medicalchatbot.backend.dto.response;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

public record NotificationItem(
        UUID id,
        String type,
        String title,
        String content,
        @JsonProperty("is_read")
        boolean isRead,
        @JsonProperty("created_at")
        OffsetDateTime createdAt
) {
}
