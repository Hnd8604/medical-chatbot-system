package com.medicalchatbot.backend.dto.response;

import java.util.List;

public record NotificationListResponse(long unreadCount, List<NotificationResponse> notifications) {
}
