package com.medicalchatbot.backend.mapper;

import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.medicalchatbot.backend.dto.chatbot.ChatbotChatResponse;
import com.medicalchatbot.backend.dto.response.ChatResponse;
import com.medicalchatbot.backend.entity.ChatMemory;
import com.medicalchatbot.backend.util.TextUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Turns a chatbot-service reply into the API response, stored message metadata and session memory. */
@Component
@RequiredArgsConstructor
public class ChatbotResponseMapper {

    private final ObjectMapper objectMapper;

    public ChatResponse toResponse(UUID sessionId, UUID messageId, ChatbotChatResponse reply) {
        return new ChatResponse(
                sessionId,
                messageId,
                reply.answer(),
                reply.intent(),
                reply.toolName(),
                reply.intentSource(),
                reply.answerSource(),
                reply.answerReason(),
                reply.patientId(),
                reply.observationType(),
                reply.allPatients(),
                reply.patientSearch(),
                reply.needsPatientSelection(),
                reply.patientCandidates(),
                reply.pendingQuestion(),
                reply.evidence(),
                reply.answerUsage(),
                reply.usage(),
                reply.savedUsage(),
                reply.traceId(),
                reply.responseStatus(),
                reply.agentRoute(),
                reply.failedSteps(),
                reply.grounding(),
                reply.llmUnavailableReason()
        );
    }

    public ObjectNode userMessageMetadata(String requestPatientId, String effectivePatientId) {
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("request_patient_id", requestPatientId);
        metadata.put("effective_patient_id", effectivePatientId);
        return metadata;
    }

    /**
     * Metadata stored with the assistant message. It keeps the outcome of the turn so that reloaded
     * history shows the same notices as the live answer.
     */
    public ObjectNode assistantMessageMetadata(ChatbotChatResponse reply) {
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("intent", reply.intent());
        metadata.put("tool_name", reply.toolName());
        metadata.put("patient_id", reply.patientId());
        metadata.put("answer_source", reply.answerSource());
        metadata.put("response_status", reply.responseStatus());
        metadata.put("trace_id", reply.traceId());
        metadata.set("grounding", reply.grounding());
        metadata.put("llm_unavailable_reason", reply.llmUnavailableReason());
        metadata.set("evidence_refs", evidenceRefs(reply));
        metadata.set("knowledge_refs", knowledgeRefs(reply));
        metadata.set("memory_update", reply.memoryUpdate());
        return metadata;
    }

    /**
     * Merges the memory update of a turn into the session memory. The patient/resource focus only
     * moves when the turn was about one concrete patient — not when it listed all patients or asked
     * the user to pick one.
     */
    public ChatMemory nextMemory(ChatMemory current, String effectivePatientId, ChatbotChatResponse reply) {
        JsonNode update = reply.memoryUpdate() == null ? MissingNode.getInstance() : reply.memoryUpdate();
        String activePatientId = current.activePatientId();
        String lastResourceType = current.lastResourceType();
        String lastResourceId = current.lastResourceId();

        boolean aboutOnePatient = !reply.coversAllPatients() && !reply.requiresPatientSelection();
        if (aboutOnePatient) {
            activePatientId = TextUtils.firstNonBlank(
                    text(update, "active_patient_id"), reply.patientId(), effectivePatientId, activePatientId);
            lastResourceType = TextUtils.firstNonBlank(
                    text(update, "last_resource_type"), firstEvidenceRef(reply, "resource_type"), lastResourceType);
            lastResourceId = TextUtils.firstNonBlank(
                    text(update, "last_resource_id"), firstEvidenceRef(reply, "resource_id"), lastResourceId);
        }

        return new ChatMemory(
                activePatientId,
                TextUtils.firstNonBlank(text(update, "summary"), current.summary()),
                TextUtils.firstNonBlank(text(update, "last_intent"), reply.intent(), current.lastIntent()),
                TextUtils.firstNonBlank(text(update, "last_tool_name"), reply.toolName(), current.lastToolName()),
                lastResourceType,
                lastResourceId
        );
    }

    /** Operational alert details for a turn whose data retrieval failed entirely. */
    public ObjectNode toolErrorAlertMetadata(ChatbotChatResponse reply) {
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("trace_id", reply.traceId());
        metadata.put("tool_name", reply.toolName());
        metadata.set("failed_steps", reply.failedSteps());
        return metadata;
    }

    ArrayNode knowledgeRefs(ChatbotChatResponse reply) {
        ArrayNode refs = objectMapper.createArrayNode();
        JsonNode knowledge = reply.externalKnowledge();
        if (knowledge == null || !knowledge.isArray()) {
            return refs;
        }
        for (JsonNode item : knowledge) {
            ObjectNode ref = refs.addObject();
            for (String field : new String[]{"source", "type", "system", "code", "display", "url"}) {
                ref.put(field, text(item, field));
            }
        }
        return refs;
    }

    /** References to the evidence used, preferring the compact refs chatbot-service put in the memory update. */
    private ArrayNode evidenceRefs(ChatbotChatResponse reply) {
        ArrayNode refs = objectMapper.createArrayNode();
        JsonNode memoryRefs = memoryEvidenceRefs(reply);
        if (memoryRefs.isArray()) {
            memoryRefs.forEach(refs::add);
            return refs;
        }
        JsonNode evidence = reply.evidence();
        if (evidence == null || !evidence.isArray()) {
            return refs;
        }
        for (JsonNode item : evidence) {
            ObjectNode ref = refs.addObject();
            ref.put("resource_type", text(item, "resource_type"));
            ref.put("resource_id", text(item, "id"));
            ref.put("summary", text(item, "summary"));
        }
        return refs;
    }

    private String firstEvidenceRef(ChatbotChatResponse reply, String field) {
        JsonNode memoryRefs = memoryEvidenceRefs(reply);
        if (memoryRefs.isArray() && !memoryRefs.isEmpty()) {
            String value = text(memoryRefs.get(0), field);
            if (value != null) {
                return value;
            }
        }
        JsonNode evidence = reply.evidence();
        if (evidence == null || !evidence.isArray() || evidence.isEmpty()) {
            return null;
        }
        // Raw evidence items name their id "id" rather than "resource_id".
        return text(evidence.get(0), "resource_id".equals(field) ? "id" : field);
    }

    private static JsonNode memoryEvidenceRefs(ChatbotChatResponse reply) {
        return reply.memoryUpdate() == null ? MissingNode.getInstance() : reply.memoryUpdate().path("evidence_refs");
    }

    /** Text value of {@code field}, or {@code null} when missing, JSON null or blank. */
    static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        return TextUtils.trimToNull(value.asText(null));
    }
}
