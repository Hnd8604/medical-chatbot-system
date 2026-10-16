package com.medicalchatbot.backend.controller;

import java.util.UUID;

import com.medicalchatbot.backend.dto.response.NotificationListResponse;
import com.medicalchatbot.backend.service.CurrentUserService;
import com.medicalchatbot.backend.service.NotificationService;
import com.medicalchatbot.backend.service.NotificationStreamService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService notificationService;
    private final NotificationStreamService notificationStreamService;
    private final CurrentUserService currentUserService;

    @GetMapping
    public NotificationListResponse list() {
        return notificationService.list(currentUserService.requireCurrentUserId());
    }

    /**
     * Server-Sent Events: {@code connected} once the stream is open, then one {@code notification}
     * event per new notification. Accepts {@code ?token=} because EventSource cannot send headers.
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return notificationStreamService.subscribe(currentUserService.requireCurrentUserId());
    }

    @PostMapping("/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markAsRead(@PathVariable UUID id) {
        notificationService.markAsRead(currentUserService.requireCurrentUserId(), id);
    }

    @PostMapping("/read-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markAllAsRead() {
        notificationService.markAllAsRead(currentUserService.requireCurrentUserId());
    }
}
