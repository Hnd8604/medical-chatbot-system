package com.medicalchatbot.backend.dto.response;

import com.fasterxml.jackson.databind.JsonNode;
import com.medicalchatbot.backend.enums.AlertSeverity;
import com.medicalchatbot.backend.enums.AlertStatus;
import java.time.OffsetDateTime;
import java.util.UUID;

/** API projection for an alert; persistence entities stay inside the service layer. */
public record AlertResponse(
        UUID id,
        String source,
        String alertType,
        AlertSeverity severity,
        AlertStatus status,
        String message,
        JsonNode metadataJson,
        OffsetDateTime createdAt,
        OffsetDateTime resolvedAt,
        String resolvedBy
) {
}
