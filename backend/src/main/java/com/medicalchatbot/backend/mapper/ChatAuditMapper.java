package com.medicalchatbot.backend.mapper;

import static com.medicalchatbot.backend.enums.AuditAction.ASK_UNSUPPORTED;
import static com.medicalchatbot.backend.enums.AuditAction.CHAT_CLARIFICATION;
import static com.medicalchatbot.backend.enums.AuditAction.CHAT_COMPLETED;
import static com.medicalchatbot.backend.enums.AuditAction.CHAT_TOOL_ERROR;
import static com.medicalchatbot.backend.enums.AuditAction.EXPLAIN_CONCEPT;
import static com.medicalchatbot.backend.enums.AuditAction.SEARCH_PATIENTS;
import static com.medicalchatbot.backend.enums.AuditAction.VIEW_ALL_PATIENT_CONDITIONS;
import static com.medicalchatbot.backend.enums.AuditAction.VIEW_ALL_PATIENT_ENCOUNTERS;
import static com.medicalchatbot.backend.enums.AuditAction.VIEW_ALL_PATIENT_MEDICATIONS;
import static com.medicalchatbot.backend.enums.AuditAction.VIEW_ALL_PATIENT_OBSERVATIONS;
import static com.medicalchatbot.backend.enums.AuditAction.VIEW_CONDITIONS;
import static com.medicalchatbot.backend.enums.AuditAction.VIEW_ENCOUNTERS;
import static com.medicalchatbot.backend.enums.AuditAction.VIEW_FROM_CACHE;
import static com.medicalchatbot.backend.enums.AuditAction.VIEW_MEDICATIONS;
import static com.medicalchatbot.backend.enums.AuditAction.VIEW_OBSERVATIONS;
import static com.medicalchatbot.backend.enums.AuditAction.VIEW_PATIENT_DATA;
import static com.medicalchatbot.backend.enums.AuditAction.VIEW_PATIENT_DETAIL;
import static com.medicalchatbot.backend.enums.AuditAction.VIEW_RESOURCE;

import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.medicalchatbot.backend.dto.chatbot.ChatbotChatResponse;
import com.medicalchatbot.backend.enums.AuditAction;
import com.medicalchatbot.backend.enums.AuditResource;
import com.medicalchatbot.backend.util.TextUtils;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.stereotype.Component;

/** Classifies a completed chat turn as an audit entry: which patient data was accessed and how. */
@Component
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class ChatAuditMapper {

    static final String ALL_PATIENTS_ID = "all_patients";
    static final String SEARCH_QUERY_ID = "search_query";

    /** Which id identifies the accessed resource of a tool. */
    private enum Target { SESSION, PATIENT, ALL_PATIENTS, SEARCH_QUERY, PATIENT_OR_ALL }

    private record ToolAudit(AuditAction action, AuditResource resource, Target target) {
    }

    private static final ToolAudit PLAIN_CHAT = new ToolAudit(CHAT_COMPLETED, AuditResource.CHAT_SESSION, Target.SESSION);

    /** Audit classification per chatbot-service tool; unknown tools are recorded as a plain chat turn. */
    private static final Map<String, ToolAudit> TOOL_AUDITS = Map.ofEntries(
            Map.entry("get_patient_by_id", new ToolAudit(VIEW_PATIENT_DETAIL, AuditResource.PATIENT, Target.PATIENT)),
            Map.entry("get_observations", new ToolAudit(VIEW_OBSERVATIONS, AuditResource.OBSERVATION, Target.PATIENT)),
            Map.entry("get_conditions", new ToolAudit(VIEW_CONDITIONS, AuditResource.CONDITION, Target.PATIENT)),
            Map.entry("get_encounters", new ToolAudit(VIEW_ENCOUNTERS, AuditResource.ENCOUNTER, Target.PATIENT)),
            Map.entry("get_medication_requests",
                    new ToolAudit(VIEW_MEDICATIONS, AuditResource.MEDICATION_REQUEST, Target.PATIENT)),
            Map.entry("get_resource_by_id", new ToolAudit(VIEW_RESOURCE, AuditResource.FHIR_RESOURCE, Target.PATIENT)),
            Map.entry("search_patients", new ToolAudit(SEARCH_PATIENTS, AuditResource.PATIENT, Target.SEARCH_QUERY)),
            Map.entry("get_all_patient_observations",
                    new ToolAudit(VIEW_ALL_PATIENT_OBSERVATIONS, AuditResource.OBSERVATION, Target.ALL_PATIENTS)),
            Map.entry("get_all_patient_conditions",
                    new ToolAudit(VIEW_ALL_PATIENT_CONDITIONS, AuditResource.CONDITION, Target.ALL_PATIENTS)),
            Map.entry("get_all_patient_encounters",
                    new ToolAudit(VIEW_ALL_PATIENT_ENCOUNTERS, AuditResource.ENCOUNTER, Target.ALL_PATIENTS)),
            Map.entry("get_all_patient_medication_requests",
                    new ToolAudit(VIEW_ALL_PATIENT_MEDICATIONS, AuditResource.MEDICATION_REQUEST, Target.ALL_PATIENTS)),
            // Multi-step plan: the individual tools are listed in metadata.plan_steps.
            Map.entry("multi_tool_plan", new ToolAudit(VIEW_PATIENT_DATA, AuditResource.PATIENT, Target.PATIENT_OR_ALL)),
            Map.entry("cache_hit", new ToolAudit(VIEW_FROM_CACHE, AuditResource.CACHE, Target.PATIENT)),
            Map.entry("explain_concept", new ToolAudit(EXPLAIN_CONCEPT, AuditResource.CHAT_SESSION, Target.SESSION)),
            Map.entry("unsupported_question", new ToolAudit(ASK_UNSUPPORTED, AuditResource.CHAT_SESSION, Target.SESSION))
    );

    ObjectMapper objectMapper;
    ChatbotResponseMapper chatbotResponseMapper;

    public ChatAudit toChatAudit(
            UUID sessionId,
            String requestPatientId,
            String effectivePatientId,
            ChatbotChatResponse reply,
            long latencyMs
    ) {
        String patientId = TextUtils.trimToNull(reply.patientId());
        ToolAudit toolAudit = reply.toolName() == null ? PLAIN_CHAT : TOOL_AUDITS.getOrDefault(reply.toolName(), PLAIN_CHAT);

        AuditAction action = toolAudit.action();
        AuditResource resource = toolAudit.resource();
        String resourceId = switch (toolAudit.target()) {
            case SESSION -> sessionId.toString();
            case PATIENT -> patientId;
            case ALL_PATIENTS -> ALL_PATIENTS_ID;
            case SEARCH_QUERY -> SEARCH_QUERY_ID;
            case PATIENT_OR_ALL -> reply.coversAllPatients() ? ALL_PATIENTS_ID : patientId;
        };

        // A turn that returned no data is not recorded as a VIEW_*/SEARCH_* access: the agent asked
        // the user to clarify, or every retrieval failed. Attempted tools stay in metadata.plan_steps.
        if (reply.asksForClarification() || reply.hasToolError()) {
            action = reply.hasToolError() ? CHAT_TOOL_ERROR : CHAT_CLARIFICATION;
            resource = AuditResource.CHAT_SESSION;
            resourceId = sessionId.toString();
        }
        if (resourceId == null) {
            resourceId = effectivePatientId != null ? effectivePatientId : sessionId.toString();
        }

        return new ChatAudit(action, resource, resourceId,
                metadata(requestPatientId, effectivePatientId, reply, latencyMs));
    }

    private ObjectNode metadata(
            String requestPatientId,
            String effectivePatientId,
            ChatbotChatResponse reply,
            long latencyMs
    ) {
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("operation", "chat");
        metadata.put("latency_ms", latencyMs);
        metadata.put("intent", reply.intent());
        // Read by the intent analytics query.
        metadata.put("question_intent", reply.intent());
        metadata.put("tool_name", reply.toolName());
        metadata.put("intent_source", reply.intentSource());
        metadata.put("answer_source", reply.answerSource());
        metadata.put("patient_id", reply.patientId());
        metadata.put("request_patient_id", requestPatientId);
        metadata.put("effective_patient_id", effectivePatientId);
        metadata.put("llm_provider", reply.llmProvider());
        metadata.put("llm_model", reply.llmModel());
        metadata.set("usage", reply.usage());
        metadata.set("summary_usage", reply.summaryUsage());
        metadata.set("memory_update", reply.memoryUpdate());
        metadata.set("knowledge_refs", chatbotResponseMapper.knowledgeRefs(reply));
        metadata.put("trace_id", reply.traceId());
        metadata.put("response_status", reply.responseStatus());
        metadata.put("agent_route", reply.agentRoute());
        metadata.set("plan_steps", reply.planSteps());
        metadata.set("failed_steps", reply.failedSteps());
        metadata.set("grounding", reply.grounding());
        metadata.put("safety_flag", reply.safetyFlag());
        metadata.put("llm_unavailable_reason", reply.llmUnavailableReason());
        metadata.put("llm_call_count", reply.llmCalls().size());
        if (reply.needsPatientSelection() != null) {
            metadata.put("needs_patient_selection", reply.needsPatientSelection());
        }
        return metadata;
    }

    public record ChatAudit(AuditAction action, AuditResource resource, String resourceId, ObjectNode metadata) {
    }
}
