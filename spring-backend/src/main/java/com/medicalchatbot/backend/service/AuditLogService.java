package com.medicalchatbot.backend.service;

import com.medicalchatbot.backend.entity.AuditLog;
import com.medicalchatbot.backend.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;
    @Transactional(readOnly = true)
    public Page<AuditLog> searchAuditLogs(
            UUID userId,
            String action,
            String resourceType,
            String resourceId,
            OffsetDateTime fromDate,
            OffsetDateTime toDate,
            Pageable pageable
    ) {
        log.debug("Searching audit logs with filters: action={}, resourceType={}", action, resourceType);

        return auditLogRepository.searchAuditLogs(
                userId,
                action,
                resourceType,
                resourceId,
                fromDate,
                toDate,
                pageable
        );
    }
}