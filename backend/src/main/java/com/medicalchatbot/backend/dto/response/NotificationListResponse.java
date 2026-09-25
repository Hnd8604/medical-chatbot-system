package com.medicalchatbot.backend.dto.response;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

public record NotificationListResponse(
        @JsonProperty("unread_count")
        long unreadCount,
        List<NotificationItem> notifications
) {
}
