package com.medicalchatbot.backend.dto.response;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.medicalchatbot.backend.entity.Notification;

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

    public static NotificationItem from(Notification notification) {
        return new NotificationItem(
                notification.getId(),
                notification.getType().name(),
                notification.getTitle(),
                notification.getContent(),
                notification.isRead(),
                notification.getCreatedAt()
        );
    }
}
