package com.medicalchatbot.backend.service;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.medicalchatbot.backend.dto.response.PageResponse;
import com.medicalchatbot.backend.dto.response.admin.AuditLogFiltersResponse;
import com.medicalchatbot.backend.dto.response.admin.AuditLogResponse;
import com.medicalchatbot.backend.entity.AuditLog;
import com.medicalchatbot.backend.entity.ChatSession;
import com.medicalchatbot.backend.entity.User;
import com.medicalchatbot.backend.enums.AuditAction;
import com.medicalchatbot.backend.enums.AuditResource;
import com.medicalchatbot.backend.mapper.AuditLogMapper;
import com.medicalchatbot.backend.repository.AuditLogRepository;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Writes and searches the audit trail of security- and PHI-relevant actions. */
@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class AuditService {

    AuditLogRepository auditLogRepository;
    AuditLogMapper auditLogMapper;
    ObjectMapper objectMapper;

    /** Starts a metadata object; callers add the fields specific to their event. */
    public ObjectNode metadata() {
        return objectMapper.createObjectNode();
    }

    /** Metadata of the {@code {operation, result}} shape used by account events. */
    public ObjectNode metadata(String operation, String result) {
        return metadata().put("operation", operation).put("result", result);
    }

    public void record(User user, AuditAction action, AuditResource resource, String resourceId, ObjectNode metadata) {
        record(user, null, action, resource, resourceId, metadata);
    }

    public void record(
            User user,
            ChatSession session,
            AuditAction action,
            AuditResource resource,
            String resourceId,
            ObjectNode metadata
    ) {
        auditLogRepository.save(AuditLog.builder()
                .user(user)
                .session(session)
                .action(action.name())
                .resourceType(resource.value())
                .resourceId(resourceId)
                .metadataJson(metadata != null ? metadata : metadata())
                .build());
    }

    /**
     * Records an event whose caller is about to fail (login rejected, quota exceeded...). It commits
     * in its own transaction, otherwise the caller's rollback would erase it.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordIndependently(
            User user,
            AuditAction action,
            AuditResource resource,
            String resourceId,
            ObjectNode metadata
    ) {
        record(user, null, action, resource, resourceId, metadata);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    public PageResponse<AuditLogResponse> search(
            UUID userId,
            String action,
            String resourceType,
            String resourceId,
            OffsetDateTime fromDate,
            OffsetDateTime toDate,
            Pageable pageable
    ) {
        return PageResponse.of(
                auditLogRepository.search(userId, action, resourceType, resourceId, fromDate, toDate, pageable),
                auditLogMapper::toAuditLogResponse
        );
    }

    @PreAuthorize("hasRole('ADMIN')")
    public AuditLogFiltersResponse filters() {
        return new AuditLogFiltersResponse(
                Arrays.stream(AuditAction.values()).map(Enum::name).toList(),
                Arrays.stream(AuditResource.values()).map(AuditResource::value).toList()
        );
    }
}
