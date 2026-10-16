package com.medicalchatbot.backend.repository;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.medicalchatbot.backend.entity.Alert;
import com.medicalchatbot.backend.enums.AlertSeverity;
import com.medicalchatbot.backend.enums.AlertStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AlertRepository extends JpaRepository<Alert, UUID> {

    boolean existsByAlertTypeAndStatusAndCreatedAtAfter(String alertType, AlertStatus status, OffsetDateTime since);

    /** Every filter is optional; {@code null} means "do not filter on this field". */
    @Query("""
            select a from Alert a
            where (:status is null or a.status = :status)
              and (:severity is null or a.severity = :severity)
              and (:source is null or a.source = :source)
              and (:alertType is null or a.alertType = :alertType)
              and (cast(:fromDate as timestamp) is null or a.createdAt >= :fromDate)
              and (cast(:toDate as timestamp) is null or a.createdAt <= :toDate)
            """)
    Page<Alert> search(
            @Param("status") AlertStatus status,
            @Param("severity") AlertSeverity severity,
            @Param("source") String source,
            @Param("alertType") String alertType,
            @Param("fromDate") OffsetDateTime fromDate,
            @Param("toDate") OffsetDateTime toDate,
            Pageable pageable
    );
}
