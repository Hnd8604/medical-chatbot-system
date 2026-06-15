package com.medicalchatbot.backend.dto.response;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

public record FeedbackResponse(
        UUID id,

        @JsonProperty("message_id")
        UUID messageId,

        int rating,

        String comment,

        @JsonProperty("created_at")
        OffsetDateTime createdAt
) {
}
