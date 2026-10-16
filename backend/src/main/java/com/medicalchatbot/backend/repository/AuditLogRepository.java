package com.medicalchatbot.backend.repository;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.medicalchatbot.backend.entity.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    /** Every filter is optional; {@code null} means "do not filter on this field". */
    @Query("""
            select a from AuditLog a
            where (:userId is null or a.user.id = :userId)
              and (:action is null or a.action = :action)
              and (:resourceType is null or a.resourceType = :resourceType)
              and (:resourceId is null or a.resourceId = :resourceId)
              and (cast(:fromDate as timestamp) is null or a.createdAt >= :fromDate)
              and (cast(:toDate as timestamp) is null or a.createdAt <= :toDate)
            """)
    Page<AuditLog> search(
            @Param("userId") UUID userId,
            @Param("action") String action,
            @Param("resourceType") String resourceType,
            @Param("resourceId") String resourceId,
            @Param("fromDate") OffsetDateTime fromDate,
            @Param("toDate") OffsetDateTime toDate,
            Pageable pageable
    );
}
