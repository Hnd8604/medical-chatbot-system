package com.medicalchatbot.backend.dto.response;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ChatSessionSummary(
        UUID id,

        String title,

        @JsonProperty("created_at")
        OffsetDateTime createdAt,

        @JsonProperty("updated_at")
        OffsetDateTime updatedAt,

        @JsonProperty("active_patient_id")
        String activePatientId,

        @JsonProperty("message_count")
        int messageCount,

        @JsonProperty("last_message_preview")
        String lastMessagePreview
) {
}
