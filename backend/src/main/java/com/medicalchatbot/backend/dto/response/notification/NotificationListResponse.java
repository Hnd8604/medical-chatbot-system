package com.medicalchatbot.backend.dto.response.notification;

import java.util.List;

public record NotificationListResponse(long unreadCount, List<NotificationResponse> notifications) {
}
