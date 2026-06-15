package com.medicalchatbot.backend.service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.medicalchatbot.backend.entity.Notification;
import com.medicalchatbot.backend.entity.User;
import com.medicalchatbot.backend.enums.NotificationType;
import com.medicalchatbot.backend.repository.NotificationRepository;
import com.medicalchatbot.backend.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;

    public NotificationService(NotificationRepository notificationRepository, UserRepository userRepository) {
        this.notificationRepository = notificationRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public Notification createNotification(UUID userId, NotificationType type, String title, String content) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

        Notification notification = new Notification(user, type, title, content);
        Notification saved = notificationRepository.save(notification);
        log.info("Created notification for user {}: {} - {}", user.getUsername(), title, content);

        // M25.4 Mock Email support
        if (user.getEmail() != null && !user.getEmail().isBlank()) {
            sendMockEmail(user.getEmail(), title, content);
        }

        return saved;
    }

    public List<Notification> getNotificationsForUser(UUID userId) {
        return notificationRepository.findByUser_IdOrderByCreatedAtDesc(userId);
    }

    public long getUnreadCount(UUID userId) {
        return notificationRepository.countByUser_IdAndIsReadFalse(userId);
    }

    @Transactional
    public void markAsRead(UUID userId, UUID notificationId) {
        Notification notification = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new IllegalArgumentException("Notification not found: " + notificationId));

        if (!notification.getUser().getId().equals(userId)) {
            throw new IllegalArgumentException("Access denied for notification: " + notificationId);
        }

        notification.markAsRead();
        notificationRepository.save(notification);
        log.debug("Marked notification {} as read for user {}", notificationId, userId);
    }

    @Transactional
    public void markAllAsRead(UUID userId) {
        notificationRepository.markAllAsReadForUser(userId);
        log.info("Marked all notifications as read for user {}", userId);
    }

    public boolean hasQuotaWarningBeenSentToday(UUID userId) {
        OffsetDateTime startOfDay = LocalDate.now().atStartOfDay().atOffset(ZoneOffset.UTC);
        return notificationRepository.existsByUser_IdAndTypeAndCreatedAtGreaterThanEqual(
                userId,
                NotificationType.QUOTA_WARNING,
                startOfDay
        );
    }

    private void sendMockEmail(String email, String title, String content) {
        log.info("=== [MOCK EMAIL DISPATCH] ===");
        log.info("To: {}", email);
        log.info("Subject: {}", title);
        log.info("Content: {}", content);
        log.info("=============================");
    }
}
