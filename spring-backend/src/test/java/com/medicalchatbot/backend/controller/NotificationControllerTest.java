package com.medicalchatbot.backend.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.medicalchatbot.backend.entity.Notification;
import com.medicalchatbot.backend.entity.User;
import com.medicalchatbot.backend.enums.NotificationType;
import com.medicalchatbot.backend.config.JwtAuthenticationFilter;
import com.medicalchatbot.backend.service.CurrentUserService;
import com.medicalchatbot.backend.service.NotificationService;
import com.medicalchatbot.backend.service.QuotaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(NotificationController.class)
@AutoConfigureMockMvc(addFilters = false)
class NotificationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private NotificationService notificationService;

    @MockitoBean
    private CurrentUserService currentUserService;

    @MockitoBean
    private QuotaService quotaService;

    @MockitoBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @MockitoBean
    private StringRedisTemplate stringRedisTemplate;

    private UUID userId;
    private String username;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        when(currentUserService.requireCurrentUserId()).thenReturn(userId);
    }

    @Test
    void getNotificationsReturnsList() throws Exception {
        User user = new User(userId);
        Notification notification = new Notification(user, NotificationType.QUOTA_WARNING, "Cảnh báo Quota", "Bạn đã dùng 85% hạn mức.");

        java.lang.reflect.Field idField = Notification.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(notification, UUID.randomUUID());

        java.lang.reflect.Field createdAtField = Notification.class.getDeclaredField("createdAt");
        createdAtField.setAccessible(true);
        createdAtField.set(notification, OffsetDateTime.now());

        when(notificationService.getUnreadCount(userId)).thenReturn(1L);
        when(notificationService.getNotificationsForUser(userId)).thenReturn(List.of(notification));

        mockMvc.perform(get("/api/notifications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unread_count").value(1))
                .andExpect(jsonPath("$.notifications[0].title").value("Cảnh báo Quota"))
                .andExpect(jsonPath("$.notifications[0].type").value("QUOTA_WARNING"));
    }

    @Test
    void markAsReadSucceeds() throws Exception {
        UUID notifId = UUID.randomUUID();
        doNothing().when(notificationService).markAsRead(userId, notifId);

        mockMvc.perform(post("/api/notifications/" + notifId + "/read"))
                .andExpect(status().isOk());

        verify(notificationService).markAsRead(userId, notifId);
    }

    @Test
    void markAllAsReadSucceeds() throws Exception {
        doNothing().when(notificationService).markAllAsRead(userId);

        mockMvc.perform(post("/api/notifications/read-all"))
                .andExpect(status().isOk());

        verify(notificationService).markAllAsRead(userId);
    }
}
