package com.medicalchatbot.backend.dto.response;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.medicalchatbot.backend.enums.BackupStatus;

/** A restore job; {@code items} lists each restored database ({db, file, ok, error}). */
public record RestoreResponse(
        UUID id,
        UUID backupId,
        BackupStatus status,
        String triggeredBy,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        JsonNode items,
        String errorMessage,
        OffsetDateTime createdAt
) {
}
