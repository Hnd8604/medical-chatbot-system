package com.medicalchatbot.backend.controller;

import java.util.List;
import java.util.UUID;

import com.medicalchatbot.backend.dto.response.NotificationItem;
import com.medicalchatbot.backend.dto.response.NotificationListResponse;
import com.medicalchatbot.backend.entity.Notification;
import com.medicalchatbot.backend.service.CurrentUserService;
import com.medicalchatbot.backend.service.NotificationService;
import com.medicalchatbot.backend.service.NotificationStreamService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService notificationService;
    private final NotificationStreamService notificationStreamService;
    private final CurrentUserService currentUserService;

    private UUID getCurrentUserId() {
        return currentUserService.requireCurrentUserId();
    }

    @GetMapping
    public NotificationListResponse getNotifications() {
        UUID userId = getCurrentUserId();
        long unreadCount = notificationService.getUnreadCount(userId);
        List<Notification> notifications = notificationService.getNotificationsForUser(userId);

        List<NotificationItem> items = notifications.stream()
                .map(NotificationItem::from)
                .toList();

        return new NotificationListResponse(unreadCount, items);
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        UUID userId = getCurrentUserId();
        return notificationStreamService.subscribe(userId);
    }

    @PostMapping("/{id}/read")
    public ResponseEntity<Void> markAsRead(@PathVariable UUID id) {
        UUID userId = getCurrentUserId();
        notificationService.markAsRead(userId, id);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/read-all")
    public ResponseEntity<Void> markAllAsRead() {
        UUID userId = getCurrentUserId();
        notificationService.markAllAsRead(userId);
        return ResponseEntity.ok().build();
    }
}
