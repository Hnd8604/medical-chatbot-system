package com.medicalchatbot.backend.service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

import com.medicalchatbot.backend.config.properties.AppProperties;
import com.medicalchatbot.backend.dto.response.NotificationListResponse;
import com.medicalchatbot.backend.dto.response.NotificationResponse;
import com.medicalchatbot.backend.entity.Notification;
import com.medicalchatbot.backend.enums.NotificationType;
import com.medicalchatbot.backend.exception.AppException;
import com.medicalchatbot.backend.mapper.NotificationMapper;
import com.medicalchatbot.backend.repository.NotificationRepository;
import com.medicalchatbot.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** In-app notifications, persisted and pushed live to the user's open tabs. */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final NotificationStreamService notificationStreamService;
    private final NotificationMapper notificationMapper;
    private final AppProperties appProperties;

    @Transactional
    public void notify(UUID userId, NotificationType type, String title, String content) {
        Notification saved = notificationRepository.save(Notification.builder()
                .user(userRepository.getReferenceById(userId))
                .type(type)
                .title(title)
                .content(content)
                .build());
        log.info("Created {} notification for user {}", type, userId);
        notificationStreamService.publish(userId, notificationMapper.toResponse(saved));
    }

    @Transactional(readOnly = true)
    public NotificationListResponse list(UUID userId) {
        return new NotificationListResponse(
                notificationRepository.countByUserIdAndReadFalse(userId),
                notificationRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                        .map(notificationMapper::toResponse)
                        .toList()
        );
    }

    @Transactional
    public void markAsRead(UUID userId, UUID notificationId) {
        // Another user's notification is reported as missing rather than forbidden.
        Notification notification = notificationRepository.findByIdAndUserId(notificationId, userId)
                .orElseThrow(() -> AppException.notFound("Không tìm thấy thông báo."));
        notification.setRead(true);
    }

    @Transactional
    public void markAllAsRead(UUID userId) {
        notificationRepository.markAllAsRead(userId);
    }

    /** Quota warnings are sent at most once per day (in the application time zone). */
    @Transactional(readOnly = true)
    public boolean quotaWarningSentToday(UUID userId) {
        OffsetDateTime startOfToday = LocalDate.now(appProperties.timezone())
                .atStartOfDay(appProperties.timezone())
                .toOffsetDateTime();
        return notificationRepository.existsByUserIdAndTypeAndCreatedAtGreaterThanEqual(
                userId, NotificationType.QUOTA_WARNING, startOfToday);
    }
}
