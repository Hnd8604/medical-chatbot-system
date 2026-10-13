package com.medicalchatbot.backend.mapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
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

    private static final String ALL_PATIENTS_RESOURCE_ID = "all_patients";

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
                chatbotResponse.path("saved_usage"),
                textOrNull(chatbotResponse, "trace_id"),
                textOrNull(chatbotResponse, "response_status"),
                textOrNull(chatbotResponse, "agent_route"),
                chatbotResponse.path("failed_steps"),
                chatbotResponse.path("grounding"),
                textOrNull(chatbotResponse, "llm_unavailable_reason")
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
        JsonNode savedUsage = chatbotResponse.path("saved_usage");
        boolean savedUsagePresent = !savedUsage.isMissingNode() && !savedUsage.isNull();
        String provider = textOrNull(chatbotResponse, "llm_provider");
        String model = textOrNull(chatbotResponse, "llm_model");
        List<LlmCall> calls = chatbotResponse.path("llm_calls").isArray()
                ? llmCalls(chatbotResponse)
                : unattributedCalls(chatbotResponse.path("usage"), provider, model);
        return new ChatUsage(
                provider,
                model,
                textOrNull(chatbotResponse, "answer_source"),
                textOrNull(chatbotResponse, "trace_id"),
                calls,
                savedUsagePresent,
                savedUsage.path("saved_input_tokens").asInt(0),
                savedUsage.path("saved_output_tokens").asInt(0),
                decimalOrNull(savedUsage.path("saved_cost_usd"))
        );
    }

    /** Từng LLM call trong {@code llm_calls} của response hoặc body lỗi của chatbot-service. */
    public List<LlmCall> llmCalls(JsonNode node) {
        JsonNode items = node == null ? null : node.path("llm_calls");
        if (items == null || !items.isArray()) {
            return List.of();
        }
        List<LlmCall> calls = new ArrayList<>();
        for (JsonNode item : items) {
            calls.add(new LlmCall(
                    firstNonBlank(textOrNull(item, "stage"), "unknown"),
                    textOrNull(item, "provider"),
                    textOrNull(item, "model"),
                    Math.max(0, item.path("input_tokens").asInt(0)),
                    Math.max(0, item.path("output_tokens").asInt(0)),
                    decimalOrNull(item.path("cost_usd")),
                    textOrNull(item, "call_id")
            ));
        }
        return calls;
    }

    /**
     * {@code llm_calls} trong body lỗi (429/403/5xx) của chatbot-service: các call đã tốn tiền
     * trước khi lượt hỏng. Body không đọc được thì coi như không có call nào.
     */
    public List<LlmCall> llmCallsFromErrorBody(byte[] body) {
        if (body == null || body.length == 0) {
            return List.of();
        }
        try {
            return llmCalls(objectMapper.readTree(body));
        } catch (IOException exception) {
            return List.of();
        }
    }

    /**
     * Response không có {@code llm_calls} (chatbot-service cũ): gom tổng usage thành một call
     * không rõ stage. Cost cũ chỉ là ước tính, không phải cost gateway, nên để null.
     */
    private List<LlmCall> unattributedCalls(JsonNode usage, String provider, String model) {
        int inputTokens = Math.max(0, usage.path("input_tokens").asInt(0));
        int outputTokens = Math.max(0, usage.path("output_tokens").asInt(0));
        if (inputTokens == 0 && outputTokens == 0) {
            return List.of();
        }
        return List.of(new LlmCall("unattributed", provider, model, inputTokens, outputTokens, null, null));
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
                case "get_resource_by_id" -> {
                    action = "VIEW_RESOURCE";
                    resourceType = "FhirResource";
                    resourceId = responsePatientId;
                }
                case "get_all_patient_observations" -> {
                    action = "VIEW_ALL_PATIENT_OBSERVATIONS";
                    resourceType = "Observation";
                    resourceId = ALL_PATIENTS_RESOURCE_ID;
                }
                case "get_all_patient_conditions" -> {
                    action = "VIEW_ALL_PATIENT_CONDITIONS";
                    resourceType = "Condition";
                    resourceId = ALL_PATIENTS_RESOURCE_ID;
                }
                case "get_all_patient_encounters" -> {
                    action = "VIEW_ALL_PATIENT_ENCOUNTERS";
                    resourceType = "Encounter";
                    resourceId = ALL_PATIENTS_RESOURCE_ID;
                }
                case "get_all_patient_medication_requests" -> {
                    action = "VIEW_ALL_PATIENT_MEDICATIONS";
                    resourceType = "MedicationRequest";
                    resourceId = ALL_PATIENTS_RESOURCE_ID;
                }
                case "multi_tool_plan" -> {
                    // Plan nhiều bước: các tool cụ thể nằm ở metadata.plan_steps.
                    action = "VIEW_PATIENT_DATA";
                    resourceType = "Patient";
                    resourceId = chatbotResponse.path("all_patients").asBoolean(false)
                            ? ALL_PATIENTS_RESOURCE_ID
                            : responsePatientId;
                }
                case "explain_concept" -> action = "EXPLAIN_CONCEPT";
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
        metadata.put("trace_id", textOrNull(chatbotResponse, "trace_id"));
        metadata.put("response_status", textOrNull(chatbotResponse, "response_status"));
        metadata.put("agent_route", textOrNull(chatbotResponse, "agent_route"));
        metadata.set("plan_steps", chatbotResponse.path("plan_steps"));
        metadata.set("failed_steps", chatbotResponse.path("failed_steps"));
        metadata.set("grounding", chatbotResponse.path("grounding"));
        metadata.put("safety_flag", textOrNull(chatbotResponse, "safety_flag"));
        metadata.put("llm_unavailable_reason", textOrNull(chatbotResponse, "llm_unavailable_reason"));
        metadata.put("llm_call_count", llmCalls(chatbotResponse).size());
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

    /** null = không biết (khác 0). Số âm/không đọc được cũng coi là không biết. */
    private BigDecimal decimalOrNull(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) {
            return null;
        }
        BigDecimal decimal;
        if (value.isNumber()) {
            decimal = value.decimalValue();
        } else {
            try {
                decimal = new BigDecimal(value.asText(""));
            } catch (NumberFormatException exception) {
                return null;
            }
        }
        return decimal.signum() < 0 ? null : decimal;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    /**
     * Usage của một lượt chat. Token tổng = tổng các call, để usage_logs và llm_call_usage
     * luôn khớp nhau.
     */
    public record ChatUsage(
            String llmProvider,
            String llmModel,
            String answerSource,
            String traceId,
            List<LlmCall> calls,
            boolean savedUsagePresent,
            int savedInputTokens,
            int savedOutputTokens,
            /** Cost gateway của câu trả lời gốc lúc ghi cache; null = không biết. */
            BigDecimal savedCostUsd
    ) {
        public int inputTokens() {
            return calls.stream().mapToInt(LlmCall::inputTokens).sum();
        }

        public int outputTokens() {
            return calls.stream().mapToInt(LlmCall::outputTokens).sum();
        }

        public int savedTokens() {
            return savedInputTokens + savedOutputTokens;
        }
    }

    /**
     * Một LLM call do chatbot-service báo. {@code gatewayCostUsd} null = gateway không báo
     * cost, khác với 0.
     */
    public record LlmCall(
            String stage,
            String provider,
            String model,
            int inputTokens,
            int outputTokens,
            BigDecimal gatewayCostUsd,
            String callId
    ) {
    }

    public record ChatAudit(
            String action,
            String resourceType,
            String resourceId,
            ObjectNode metadata
    ) {
    }
}
