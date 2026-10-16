package com.medicalchatbot.backend.dto.response.admin;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.medicalchatbot.backend.enums.BackupStatus;
import com.medicalchatbot.backend.enums.BackupTrigger;

/** A backup job; {@code items} lists each dumped database ({db, file, size_bytes, ok, uploaded}). */
public record BackupResponse(
        UUID id,
        BackupTrigger triggerType,
        BackupStatus status,
        String triggeredBy,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        Long totalSizeBytes,
        String uploadTarget,
        JsonNode items,
        String errorMessage,
        OffsetDateTime createdAt
) {
}
