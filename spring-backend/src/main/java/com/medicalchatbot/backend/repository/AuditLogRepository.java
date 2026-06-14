package com.medicalchatbot.backend.repository;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.medicalchatbot.backend.entity.AuditLog;
import com.medicalchatbot.backend.entity.ChatSession;
import com.medicalchatbot.backend.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    default void save(
            User user,
            ChatSession session,
            String action,
            String resourceType,
            String resourceId,
            JsonNode metadataJson
    ) {
        save(new AuditLog(user, session, action, resourceType, resourceId, metadataJson));
    }


    @Query("SELECT a FROM AuditLog a WHERE " +
            "(:userId IS NULL OR a.user.id = :userId) AND " +
            "(:action IS NULL OR a.action = :action) AND " +
            "(:resourceType IS NULL OR a.resourceType = :resourceType) AND " +
            "(:resourceId IS NULL OR a.resourceId = :resourceId) AND " +
            "(CAST(:fromDate AS timestamp) IS NULL OR a.createdAt >= :fromDate) AND " +
            "(CAST(:toDate AS timestamp) IS NULL OR a.createdAt <= :toDate)")
    Page<AuditLog> searchAuditLogs(
            @Param("userId") UUID userId,
            @Param("action") String action,
            @Param("resourceType") String resourceType,
            @Param("resourceId") String resourceId,
            @Param("fromDate") OffsetDateTime fromDate,
            @Param("toDate") OffsetDateTime toDate,
            Pageable pageable
    );


}
