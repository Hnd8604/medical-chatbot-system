package com.medicalchatbot.backend.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ChatSessionMemory(
        @JsonProperty("active_patient_id")
        String activePatientId,

        @JsonProperty("memory_summary")
        String memorySummary,

        @JsonProperty("last_intent")
        String lastIntent,

        @JsonProperty("last_tool_name")
        String lastToolName,

        @JsonProperty("last_resource_type")
        String lastResourceType,

        @JsonProperty("last_resource_id")
        String lastResourceId
) {
    public static ChatSessionMemory empty() {
        return new ChatSessionMemory(null, null, null, null, null, null);
    }
}
