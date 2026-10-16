package com.medicalchatbot.backend.dto.response.chat;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A stored chat message. Assistant messages also carry the outcome of the turn under the same
 * field names as {@link ChatResponse}, so the UI renders history and live answers alike.
 */
public record ChatMessageResponse(
        UUID id,
        String role,
        String content,
        OffsetDateTime createdAt,
        Feedback feedback,
        String responseStatus,
        String traceId,
        String answerSource,
        Grounding grounding,
        String llmUnavailableReason
) {

    public record Feedback(int rating, String comment) {
    }

    public record Grounding(boolean checked, Boolean passed) {
    }
}
