package com.medicalchatbot.backend.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.medicalchatbot.backend.entity.Notification;
import com.medicalchatbot.backend.enums.NotificationType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    List<Notification> findByUser_IdOrderByCreatedAtDesc(UUID userId);

    long countByUser_IdAndIsReadFalse(UUID userId);

    boolean existsByUser_IdAndTypeAndCreatedAtGreaterThanEqual(UUID userId, NotificationType type, OffsetDateTime since);

    @Modifying
    @Query("update Notification n set n.isRead = true where n.user.id = :userId and n.isRead = false")
    void markAllAsReadForUser(@Param("userId") UUID userId);
}
