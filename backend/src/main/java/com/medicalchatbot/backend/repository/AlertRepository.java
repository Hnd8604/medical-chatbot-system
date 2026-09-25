package com.medicalchatbot.backend.repository;

import com.medicalchatbot.backend.entity.Alert;
import com.medicalchatbot.backend.enums.AlertSeverity;
import com.medicalchatbot.backend.enums.AlertStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.UUID;

@Repository
public interface AlertRepository extends JpaRepository<Alert, UUID> {


    boolean existsByAlertTypeAndStatusAndCreatedAtAfter(String alertType, AlertStatus status, OffsetDateTime timeThreshold);

    @Query("SELECT a FROM Alert a WHERE " +
            "(:status IS NULL OR a.status = :status) AND " +
            "(:severity IS NULL OR a.severity = :severity) AND " +
            "(:source IS NULL OR a.source = :source) AND " +
            "(:alertType IS NULL OR a.alertType = :alertType) AND " +
            "(CAST(:fromDate AS timestamp) IS NULL OR a.createdAt >= :fromDate) AND " +
            "(CAST(:toDate AS timestamp) IS NULL OR a.createdAt <= :toDate)")
    Page<Alert> searchAlerts(
            @Param("status") AlertStatus status,
            @Param("severity") AlertSeverity severity,
            @Param("source") String source,
            @Param("alertType") String alertType,
            @Param("fromDate") OffsetDateTime fromDate,
            @Param("toDate") OffsetDateTime toDate,
            Pageable pageable
    );
}