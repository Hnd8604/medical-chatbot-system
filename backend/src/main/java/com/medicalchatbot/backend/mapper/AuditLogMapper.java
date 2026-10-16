package com.medicalchatbot.backend.mapper;

import com.medicalchatbot.backend.dto.response.admin.AuditLogResponse;
import com.medicalchatbot.backend.entity.AuditLog;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper
public interface AuditLogMapper {

    @Mapping(target = "metadata", source = "metadataJson")
    AuditLogResponse toAuditLogResponse(AuditLog auditLog);
}
