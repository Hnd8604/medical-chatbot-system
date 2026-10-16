package com.medicalchatbot.backend.dto.response.admin;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.medicalchatbot.backend.enums.AlertSeverity;
import com.medicalchatbot.backend.enums.AlertStatus;

public record AlertResponse(
        UUID id,
        String source,
        String alertType,
        AlertSeverity severity,
        AlertStatus status,
        String message,
        JsonNode metadata,
        OffsetDateTime createdAt,
        OffsetDateTime resolvedAt,
        String resolvedBy
) {
}
