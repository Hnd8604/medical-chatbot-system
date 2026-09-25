package com.medicalchatbot.backend.dto.response;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

public record ChatResponse(
        @JsonProperty("session_id")
        UUID sessionId,

        @JsonProperty("message_id")
        UUID messageId,

        String answer,

        String intent,

        @JsonProperty("tool_name")
        String toolName,

        @JsonProperty("intent_source")
        String intentSource,

        @JsonProperty("answer_source")
        String answerSource,

        @JsonProperty("answer_reason")
        String answerReason,

        @JsonProperty("patient_id")
        String patientId,

        @JsonProperty("observation_type")
        String observationType,

        @JsonProperty("all_patients")
        Boolean allPatients,

        @JsonProperty("patient_search")
        JsonNode patientSearch,

        @JsonProperty("needs_patient_selection")
        Boolean needsPatientSelection,

        @JsonProperty("patient_candidates")
        JsonNode patientCandidates,

        @JsonProperty("pending_question")
        String pendingQuestion,

        JsonNode evidence,

        @JsonProperty("answer_usage")
        JsonNode answerUsage,

        JsonNode usage,

        @JsonProperty("saved_usage")
        JsonNode savedUsage
) {
}
