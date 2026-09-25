package com.medicalchatbot.backend.mapper;

import java.math.BigDecimal;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.medicalchatbot.backend.dto.request.ChatRequest;
import com.medicalchatbot.backend.dto.response.ChatResponse;
import com.medicalchatbot.backend.dto.response.ChatSessionMemory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Translates the chatbot service's JSON contract into application-owned DTOs and metadata.
 */
@Component
@RequiredArgsConstructor
public class ChatbotResponseMapper {

    private final ObjectMapper objectMapper;

    public String answer(JsonNode chatbotResponse) {
        return chatbotResponse.path("answer").asText("");
    }

    public ChatResponse toResponse(UUID sessionId, UUID messageId, JsonNode chatbotResponse) {
        return new ChatResponse(
                sessionId,
                messageId,
                answer(chatbotResponse),
                chatbotResponse.path("intent").asText(null),
                chatbotResponse.path("tool_name").asText(null),
                chatbotResponse.path("intent_source").asText(null),
                chatbotResponse.path("answer_source").asText(null),
                chatbotResponse.path("answer_reason").asText(null),
                chatbotResponse.path("patient_id").asText(null),
                chatbotResponse.path("observation_type").asText(null),
                chatbotResponse.has("all_patients")
                        ? chatbotResponse.path("all_patients").asBoolean(false)
                        : null,
                chatbotResponse.path("patient_search"),
                chatbotResponse.has("needs_patient_selection")
                        ? chatbotResponse.path("needs_patient_selection").asBoolean(false)
                        : null,
                chatbotResponse.path("patient_candidates"),
                chatbotResponse.path("pending_question").asText(null),
                chatbotResponse.path("evidence"),
                chatbotResponse.path("answer_usage"),
                chatbotResponse.path("usage"),
                chatbotResponse.path("saved_usage")
        );
    }

    public ObjectNode userMessageMetadata(ChatRequest request, String effectivePatientId) {
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("request_patient_id", request.patientId());
        metadata.put("effective_patient_id", effectivePatientId);
        return metadata;
    }

    public ObjectNode assistantMessageMetadata(JsonNode chatbotResponse) {
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("intent", textOrNull(chatbotResponse, "intent"));
        metadata.put("tool_name", textOrNull(chatbotResponse, "tool_name"));
        metadata.put("patient_id", textOrNull(chatbotResponse, "patient_id"));
        metadata.put("answer_source", textOrNull(chatbotResponse, "answer_source"));
        metadata.set("evidence_refs", evidenceRefs(chatbotResponse));
        metadata.set("knowledge_refs", knowledgeRefs(chatbotResponse));
        metadata.set("memory_update", chatbotResponse.path("memory_update"));
        return metadata;
    }

    public ChatSessionMemory nextSessionMemory(
            ChatSessionMemory current,
            String effectivePatientId,
            JsonNode chatbotResponse
    ) {
        JsonNode memoryUpdate = chatbotResponse.path("memory_update");
        boolean allPatients = chatbotResponse.path("all_patients").asBoolean(false);
        boolean needsPatientSelection = chatbotResponse.path("needs_patient_selection").asBoolean(false);
        boolean concretePatientResponse = !allPatients && !needsPatientSelection;

        String activePatientId = current.activePatientId();
        if (concretePatientResponse) {
            activePatientId = firstNonBlank(
                    textOrNull(memoryUpdate, "active_patient_id"),
                    textOrNull(chatbotResponse, "patient_id"),
                    effectivePatientId,
                    activePatientId
            );
        }

        String lastResourceType = current.lastResourceType();
        String lastResourceId = current.lastResourceId();
        if (concretePatientResponse) {
            lastResourceType = firstNonBlank(
                    textOrNull(memoryUpdate, "last_resource_type"),
                    firstEvidenceRefField(chatbotResponse, "resource_type"),
                    lastResourceType
            );
            lastResourceId = firstNonBlank(
                    textOrNull(memoryUpdate, "last_resource_id"),
                    firstEvidenceRefField(chatbotResponse, "resource_id"),
                    lastResourceId
            );
        }

        return new ChatSessionMemory(
                activePatientId,
                firstNonBlank(textOrNull(memoryUpdate, "summary"), current.memorySummary()),
                firstNonBlank(
                        textOrNull(memoryUpdate, "last_intent"),
                        textOrNull(chatbotResponse, "intent"),
                        current.lastIntent()
                ),
                firstNonBlank(
                        textOrNull(memoryUpdate, "last_tool_name"),
                        textOrNull(chatbotResponse, "tool_name"),
                        current.lastToolName()
                ),
                lastResourceType,
                lastResourceId
        );
    }

    public ChatUsage usage(JsonNode chatbotResponse) {
        JsonNode usage = chatbotResponse.path("usage");
        JsonNode savedUsage = chatbotResponse.path("saved_usage");
        boolean savedUsagePresent = !savedUsage.isMissingNode() && !savedUsage.isNull();
        return new ChatUsage(
                textOrNull(chatbotResponse, "llm_provider"),
                textOrNull(chatbotResponse, "llm_model"),
                textOrNull(chatbotResponse, "answer_source"),
                usage.path("input_tokens").asInt(0),
                usage.path("output_tokens").asInt(0),
                decimalOrZero(usage.path("estimated_cost_usd")),
                savedUsagePresent,
                savedUsage.path("saved_input_tokens").asInt(0),
                savedUsage.path("saved_output_tokens").asInt(0)
        );
    }

    public ChatAudit audit(
            UUID sessionId,
            ChatRequest request,
            String effectivePatientId,
            JsonNode chatbotResponse,
            long latencyMs
    ) {
        String toolName = textOrNull(chatbotResponse, "tool_name");
        String responsePatientId = textOrNull(chatbotResponse, "patient_id");
        String action = "CHAT_COMPLETED";
        String resourceType = "chat_session";
        String resourceId = sessionId.toString();

        if (toolName != null) {
            switch (toolName) {
                case "get_medication_requests" -> {
                    action = "VIEW_MEDICATIONS";
                    resourceType = "MedicationRequest";
                    resourceId = responsePatientId;
                }
                case "get_observations" -> {
                    action = "VIEW_OBSERVATIONS";
                    resourceType = "Observation";
                    resourceId = responsePatientId;
                }
                case "get_conditions" -> {
                    action = "VIEW_CONDITIONS";
                    resourceType = "Condition";
                    resourceId = responsePatientId;
                }
                case "get_encounters" -> {
                    action = "VIEW_ENCOUNTERS";
                    resourceType = "Encounter";
                    resourceId = responsePatientId;
                }
                case "get_patient_by_id" -> {
                    action = "VIEW_PATIENT_DETAIL";
                    resourceType = "Patient";
                    resourceId = responsePatientId;
                }
                case "search_patients" -> {
                    action = "SEARCH_PATIENTS";
                    resourceType = "Patient";
                    resourceId = "search_query";
                }
                case "cache_hit" -> {
                    action = "VIEW_FROM_CACHE";
                    resourceType = "Cache";
                    resourceId = responsePatientId;
                }
                case "unsupported_question" -> action = "ASK_UNSUPPORTED";
                default -> {
                }
            }
        }

        if (resourceId == null) {
            resourceId = effectivePatientId != null ? effectivePatientId : sessionId.toString();
        }

        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("operation", "chat");
        metadata.put("latency_ms", latencyMs);
        String intent = textOrNull(chatbotResponse, "intent");
        metadata.put("intent", intent);
        metadata.put("question_intent", intent);
        metadata.put("tool_name", toolName);
        metadata.put("intent_source", textOrNull(chatbotResponse, "intent_source"));
        metadata.put("answer_source", textOrNull(chatbotResponse, "answer_source"));
        metadata.put("patient_id", responsePatientId);
        metadata.put("request_patient_id", request.patientId());
        metadata.put("effective_patient_id", effectivePatientId);
        metadata.put("llm_provider", textOrNull(chatbotResponse, "llm_provider"));
        metadata.put("llm_model", textOrNull(chatbotResponse, "llm_model"));
        metadata.set("usage", chatbotResponse.path("usage"));
        metadata.set("summary_usage", chatbotResponse.path("summary_usage"));
        metadata.set("memory_update", chatbotResponse.path("memory_update"));
        metadata.set("knowledge_refs", knowledgeRefs(chatbotResponse));
        if (chatbotResponse.has("needs_patient_selection")) {
            metadata.put("needs_patient_selection", chatbotResponse.path("needs_patient_selection").asBoolean(false));
        }

        return new ChatAudit(action, resourceType, resourceId, metadata);
    }

    private ArrayNode knowledgeRefs(JsonNode chatbotResponse) {
        ArrayNode refs = objectMapper.createArrayNode();
        JsonNode knowledge = chatbotResponse.path("external_knowledge");
        if (!knowledge.isArray()) {
            return refs;
        }
        for (JsonNode item : knowledge) {
            ObjectNode ref = objectMapper.createObjectNode();
            ref.put("source", textOrNull(item, "source"));
            ref.put("type", textOrNull(item, "type"));
            ref.put("system", textOrNull(item, "system"));
            ref.put("code", textOrNull(item, "code"));
            ref.put("display", textOrNull(item, "display"));
            ref.put("url", textOrNull(item, "url"));
            refs.add(ref);
        }
        return refs;
    }

    private ArrayNode evidenceRefs(JsonNode chatbotResponse) {
        ArrayNode refs = objectMapper.createArrayNode();
        JsonNode memoryRefs = chatbotResponse.path("memory_update").path("evidence_refs");
        if (memoryRefs.isArray()) {
            memoryRefs.forEach(refs::add);
            return refs;
        }

        JsonNode evidence = chatbotResponse.path("evidence");
        if (!evidence.isArray()) {
            return refs;
        }
        for (JsonNode item : evidence) {
            ObjectNode ref = objectMapper.createObjectNode();
            ref.put("resource_type", textOrNull(item, "resource_type"));
            ref.put("resource_id", textOrNull(item, "id"));
            ref.put("summary", textOrNull(item, "summary"));
            refs.add(ref);
        }
        return refs;
    }

    private String firstEvidenceRefField(JsonNode chatbotResponse, String fieldName) {
        JsonNode memoryRefs = chatbotResponse.path("memory_update").path("evidence_refs");
        if (memoryRefs.isArray() && !memoryRefs.isEmpty()) {
            String value = textOrNull(memoryRefs.get(0), fieldName);
            if (value != null) {
                return value;
            }
        }

        JsonNode evidence = chatbotResponse.path("evidence");
        if (!evidence.isArray() || evidence.isEmpty()) {
            return null;
        }
        String evidenceField = "resource_id".equals(fieldName) ? "id" : fieldName;
        return textOrNull(evidence.get(0), evidenceField);
    }

    private String textOrNull(JsonNode node, String fieldName) {
        JsonNode value = node.path(fieldName);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String text = value.asText(null);
        return text == null || text.isBlank() ? null : text;
    }

    private BigDecimal decimalOrZero(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) {
            return BigDecimal.ZERO;
        }
        if (value.isNumber()) {
            return value.decimalValue();
        }
        try {
            return new BigDecimal(value.asText("0"));
        } catch (NumberFormatException exception) {
            return BigDecimal.ZERO;
        }
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    public record ChatUsage(
            String llmProvider,
            String llmModel,
            String answerSource,
            int inputTokens,
            int outputTokens,
            BigDecimal reportedEstimatedCostUsd,
            boolean savedUsagePresent,
            int savedInputTokens,
            int savedOutputTokens
    ) {
        public int savedTokens() {
            return savedInputTokens + savedOutputTokens;
        }
    }

    public record ChatAudit(
            String action,
            String resourceType,
            String resourceId,
            ObjectNode metadata
    ) {
    }
}
