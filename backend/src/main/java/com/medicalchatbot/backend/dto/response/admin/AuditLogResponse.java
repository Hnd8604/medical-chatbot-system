package com.medicalchatbot.backend.dto.response.admin;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

public record AuditLogResponse(
        UUID id,
        UUID userId,
        UUID sessionId,
        String action,
        String resourceType,
        String resourceId,
        JsonNode metadata,
        OffsetDateTime createdAt
) {
}
