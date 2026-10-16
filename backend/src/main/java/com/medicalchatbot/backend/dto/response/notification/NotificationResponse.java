package com.medicalchatbot.backend.dto.response.notification;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.medicalchatbot.backend.enums.NotificationType;

public record NotificationResponse(
        UUID id,
        NotificationType type,
        String title,
        String content,
        boolean isRead,
        OffsetDateTime createdAt
) {
}
