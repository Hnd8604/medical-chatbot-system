package com.medicalchatbot.backend.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.medicalchatbot.backend.dto.response.notification.NotificationListResponse;
import com.medicalchatbot.backend.dto.response.notification.NotificationResponse;
import com.medicalchatbot.backend.enums.NotificationType;
import com.medicalchatbot.backend.service.NotificationService;
import com.medicalchatbot.backend.service.NotificationStreamService;
import com.medicalchatbot.backend.service.QuotaService;
import com.medicalchatbot.backend.support.TestUsers;
import org.junit.jupiter.api.AfterEach;
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
    private NotificationStreamService notificationStreamService;

    @MockitoBean
    private QuotaService quotaService;

    @MockitoBean
    private StringRedisTemplate stringRedisTemplate;

    private UUID userId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        TestUsers.signIn(userId);
    }

    @AfterEach
    void signOut() {
        TestUsers.signOut();
    }

    @Test
    void listReturnsUnreadCountAndSnakeCaseFields() throws Exception {
        NotificationResponse notification = new NotificationResponse(UUID.randomUUID(), NotificationType.QUOTA_WARNING,
                "Cảnh báo hạn mức", "Bạn đã dùng 85% hạn mức.", false, OffsetDateTime.now());
        when(notificationService.list(userId)).thenReturn(new NotificationListResponse(1, List.of(notification)));

        mockMvc.perform(get("/notifications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.result.unreadCount").value(1))
                .andExpect(jsonPath("$.result.notifications[0].title").value("Cảnh báo hạn mức"))
                .andExpect(jsonPath("$.result.notifications[0].type").value("QUOTA_WARNING"))
                .andExpect(jsonPath("$.result.notifications[0].isRead").value(false));
    }

    @Test
    void markAsReadScopesTheUpdateToTheCurrentUser() throws Exception {
        UUID notificationId = UUID.randomUUID();

        mockMvc.perform(put("/notifications/{id}/read", notificationId))
                .andExpect(status().isOk());

        verify(notificationService).markAsRead(userId, notificationId);
    }

    @Test
    void markAllAsReadScopesTheUpdateToTheCurrentUser() throws Exception {
        mockMvc.perform(put("/notifications/read-all"))
                .andExpect(status().isOk());

        verify(notificationService).markAllAsRead(userId);
    }
}
