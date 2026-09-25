package com.medicalchatbot.backend.mapper;

import com.medicalchatbot.backend.dto.response.AuditLogResponse;
import com.medicalchatbot.backend.entity.AuditLog;
import org.springframework.stereotype.Component;

@Component
public class AuditLogMapper {

    public AuditLogResponse toResponse(AuditLog auditLog) {
        return new AuditLogResponse(
                auditLog.getId(),
                auditLog.getUserId(),
                auditLog.getSessionId(),
                auditLog.getAction(),
                auditLog.getResourceType(),
                auditLog.getResourceId(),
                auditLog.getMetadataJson(),
                auditLog.getCreatedAt()
        );
    }
}
