package com.medicalchatbot.backend.dto.request;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ConversationContext(
        @JsonProperty("memory_summary")
        String memorySummary,

        @JsonProperty("active_patient_id")
        String activePatientId,

        @JsonProperty("last_intent")
        String lastIntent,

        @JsonProperty("last_tool_name")
        String lastToolName,

        @JsonProperty("last_resource_type")
        String lastResourceType,

        @JsonProperty("last_resource_id")
        String lastResourceId,

        @JsonProperty("recent_messages")
        List<ChatContextMessage> recentMessages,

        // Tổng số message của session TRƯỚC khi lưu message hiện tại; chatbot-service
        // dùng để quyết định có kích hoạt rolling summary hay không.
        @JsonProperty("total_message_count")
        Integer totalMessageCount
) {
}
