package com.medicalchatbot.backend.dto.request;

import java.util.List;

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

        @JsonProperty("allowed_patient_ids")
        List<String> allowedPatientIds,

        @JsonProperty("patient_scope")
        String patientScope,

        @JsonProperty("conversation_context")
        ConversationContext conversationContext,

        @JsonProperty("quota_used_ratio")
        Double quotaUsedRatio,

        // Virtual key LiteLLM cua user; chatbot-service dung key nay khi goi LLM de
        // gateway chan budget dung nguoi. Null => chatbot-service roi ve master key.
        @JsonProperty("llm_key")
        String llmKey
) {
}
