package com.medicalchatbot.backend.dto.response;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ChatMessageItem(
        UUID id,

        String role,

        String content,

        @JsonProperty("created_at")
        OffsetDateTime createdAt,

        Feedback feedback,

        // --- Kết quả lượt trả lời của agent, cùng tên trường với ChatResponse; chỉ có ở
        // message assistant được lưu kèm các trường này. ---
        @JsonProperty("response_status")
        String responseStatus,

        @JsonProperty("trace_id")
        String traceId,

        @JsonProperty("answer_source")
        String answerSource,

        Grounding grounding,

        @JsonProperty("llm_unavailable_reason")
        String llmUnavailableReason
) {

    public record Feedback(
            int rating,

            String comment
    ) {
    }

    public record Grounding(
            boolean checked,

            Boolean passed
    ) {
    }
}
