package com.medicalchatbot.backend.dto.response;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.medicalchatbot.backend.enums.BackupStatus;
import com.medicalchatbot.backend.enums.BackupTrigger;

/** Public backup job view, decoupled from the mutable persistence entity. */
public record BackupHistoryResponse(
        UUID id,
        BackupTrigger triggerType,
        BackupStatus status,
        String triggeredBy,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        Long totalSizeBytes,
        String uploadTarget,
        JsonNode itemsJson,
        String errorMessage,
        OffsetDateTime createdAt
) {
}
