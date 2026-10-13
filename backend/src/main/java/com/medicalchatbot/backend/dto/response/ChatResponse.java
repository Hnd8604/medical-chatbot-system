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
        JsonNode savedUsage,

        // --- Contract agent (chatbot-service) ---
        /** Mã tra log bên chatbot-service; FE hiển thị khi người dùng báo lỗi. */
        @JsonProperty("trace_id")
        String traceId,

        /** answered | partial | no_data | clarification_needed | patient_selection_needed | tool_error */
        @JsonProperty("response_status")
        String responseStatus,

        /** fhir | general_chat | conversation_meta | unsupported | cache */
        @JsonProperty("agent_route")
        String agentRoute,

        /** [{id, tool, error_code}] — các bước truy xuất bị lỗi khi response_status = partial. */
        @JsonProperty("failed_steps")
        JsonNode failedSteps,

        /** {checked, passed} — kết quả kiểm câu trả lời LLM so với evidence. */
        JsonNode grounding,

        /** Có khi lượt này không được gọi LLM (gateway chưa cấu hình) — trả lời bằng luật/template. */
        @JsonProperty("llm_unavailable_reason")
        String llmUnavailableReason
) {
}
