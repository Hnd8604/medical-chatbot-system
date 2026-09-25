package com.medicalchatbot.backend.dto.response;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.medicalchatbot.backend.enums.BackupStatus;

/** Public restore job view, decoupled from the mutable persistence entity. */
public record RestoreHistoryResponse(
        UUID id,
        UUID backupId,
        BackupStatus status,
        String triggeredBy,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        JsonNode itemsJson,
        String errorMessage,
        OffsetDateTime createdAt
) {
}
