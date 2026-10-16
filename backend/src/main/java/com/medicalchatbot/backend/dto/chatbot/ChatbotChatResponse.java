package com.medicalchatbot.backend.dto.chatbot;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * Response of {@code POST /chat} on chatbot-service (see {@code chatbot-service/api/schemas.py}).
 *
 * <p>Fields Spring reasons about are typed; structures it only stores or forwards to the
 * frontend (evidence, usage, grounding...) stay as {@link JsonNode} so new upstream fields pass
 * through without a Spring change.</p>
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatbotChatResponse(
        String traceId,
        String answer,
        /** answered | partial | no_data | clarification_needed | patient_selection_needed | tool_error */
        String responseStatus,
        /** fhir | general_chat | conversation_meta | unsupported | cache */
        String agentRoute,
        String intent,
        String toolName,
        String intentSource,
        String answerSource,
        String answerReason,
        String patientId,
        String observationType,
        Boolean allPatients,
        Boolean needsPatientSelection,
        String pendingQuestion,
        String safetyFlag,
        String llmProvider,
        String llmModel,
        /** Set when the turn was answered without an LLM (gateway not configured). */
        String llmUnavailableReason,
        List<ChatbotLlmCall> llmCalls,
        JsonNode patientSearch,
        JsonNode patientCandidates,
        JsonNode evidence,
        JsonNode externalKnowledge,
        JsonNode memoryUpdate,
        JsonNode planSteps,
        JsonNode failedSteps,
        JsonNode grounding,
        JsonNode usage,
        JsonNode answerUsage,
        JsonNode savedUsage,
        JsonNode summaryUsage
) {

    public static final String STATUS_TOOL_ERROR = "tool_error";
    public static final String STATUS_CLARIFICATION_NEEDED = "clarification_needed";

    public String answer() {
        return answer == null ? "" : answer;
    }

    public List<ChatbotLlmCall> llmCalls() {
        return llmCalls == null ? List.of() : llmCalls;
    }

    // Convenience checks deliberately avoid the get/is prefixes so Jackson does not treat them as
    // extra properties of the record.

    public boolean coversAllPatients() {
        return Boolean.TRUE.equals(allPatients);
    }

    public boolean requiresPatientSelection() {
        return Boolean.TRUE.equals(needsPatientSelection);
    }

    /** Every data retrieval step failed even though chatbot-service answered HTTP 200. */
    public boolean hasToolError() {
        return STATUS_TOOL_ERROR.equals(responseStatus);
    }

    public boolean asksForClarification() {
        return STATUS_CLARIFICATION_NEEDED.equals(responseStatus);
    }
}
