package com.medicalchatbot.backend.dto.response;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

/** Stable API projection for an audit entry; JPA relations never cross the HTTP boundary. */
public record AuditLogResponse(
        UUID id,
        UUID userId,
        UUID sessionId,
        String action,
        String resourceType,
        String resourceId,
        JsonNode metadataJson,
        OffsetDateTime createdAt
) {
}
