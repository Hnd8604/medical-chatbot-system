package com.medicalchatbot.backend.dto.response;

import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

/** Answer of one chat turn. FHIR payloads (evidence, candidates, usage...) pass through from chatbot-service. */
public record ChatResponse(
        UUID sessionId,
        /** Id of the stored assistant message; used to rate the answer. */
        UUID messageId,
        String answer,
        String intent,
        String toolName,
        String intentSource,
        String answerSource,
        String answerReason,
        String patientId,
        String observationType,
        Boolean allPatients,
        JsonNode patientSearch,
        Boolean needsPatientSelection,
        JsonNode patientCandidates,
        /** Question to re-send once the user picks one of the patient candidates. */
        String pendingQuestion,
        JsonNode evidence,
        JsonNode answerUsage,
        JsonNode usage,
        JsonNode savedUsage,
        /** Log correlation id on chatbot-service; shown to users when something goes wrong. */
        String traceId,
        /** answered, partial, no_data, clarification_needed, patient_selection_needed or tool_error. */
        String responseStatus,
        /** fhir, general_chat, conversation_meta, unsupported or cache. */
        String agentRoute,
        /** Failed retrieval steps ({id, tool, error_code}) when the answer is partial. */
        JsonNode failedSteps,
        /** {checked, passed}: whether the LLM answer was verified against the evidence. */
        JsonNode grounding,
        /** Set when the turn was answered by rules/templates because no LLM was available. */
        String llmUnavailableReason
) {
}
