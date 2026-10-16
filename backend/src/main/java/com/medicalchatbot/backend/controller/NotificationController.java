package com.medicalchatbot.backend.controller;

import java.util.UUID;

import com.medicalchatbot.backend.constant.ResponseMessage;
import com.medicalchatbot.backend.dto.response.ApiResponse;
import com.medicalchatbot.backend.dto.response.notification.NotificationListResponse;
import com.medicalchatbot.backend.service.NotificationService;
import com.medicalchatbot.backend.service.NotificationStreamService;
import com.medicalchatbot.backend.util.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/notifications")
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
@Tag(name = "Notifications", description = "In-app notifications of the signed-in user")
public class NotificationController {

    NotificationService notificationService;
    NotificationStreamService notificationStreamService;

    @GetMapping
    @Operation(summary = "List my notifications", description = "Newest first, with the number of unread ones.")
    public ApiResponse<NotificationListResponse> list() {
        return ApiResponse.<NotificationListResponse>builder()
                .result(notificationService.list(SecurityUtils.currentUserId()))
                .message(ResponseMessage.GET_NOTIFICATIONS_SUCCESS)
                .build();
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Stream my notifications",
            description = "Server-Sent Events: 'connected' once the stream is open, then one 'notification' event per "
                    + "new notification. Accepts ?token= because EventSource cannot send headers.")
    public SseEmitter stream() {
        return notificationStreamService.subscribe(SecurityUtils.currentUserId());
    }

    @PutMapping("/{id}/read")
    @Operation(summary = "Mark a notification as read")
    public ApiResponse<Void> markAsRead(@PathVariable UUID id) {
        notificationService.markAsRead(SecurityUtils.currentUserId(), id);
        return ApiResponse.<Void>builder().message(ResponseMessage.MARK_NOTIFICATION_READ_SUCCESS).build();
    }

    @PutMapping("/read-all")
    @Operation(summary = "Mark all my notifications as read")
    public ApiResponse<Void> markAllAsRead() {
        notificationService.markAllAsRead(SecurityUtils.currentUserId());
        return ApiResponse.<Void>builder().message(ResponseMessage.MARK_ALL_NOTIFICATIONS_READ_SUCCESS).build();
    }
}
