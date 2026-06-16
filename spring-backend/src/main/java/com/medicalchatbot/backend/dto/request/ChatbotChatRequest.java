package com.medicalchatbot.backend.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ChatbotChatRequest(
        @JsonProperty("user_id")
        String userId,

        @JsonProperty("user_role")
        String userRole,

        @JsonProperty("session_id")
        String sessionId,

        String message,

        @JsonProperty("patient_id")
        String patientId,

        @JsonProperty("conversation_context")
        ConversationContext conversationContext
) {
}
