package com.medicalchatbot.backend.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medicalchatbot.backend.dto.chatbot.ChatbotChatResponse;
import com.medicalchatbot.backend.enums.AuditAction;
import com.medicalchatbot.backend.enums.AuditResource;
import com.medicalchatbot.backend.mapper.ChatAuditMapper.ChatAudit;
import org.junit.jupiter.api.Test;

class ChatAuditMapperTest {

    private static final UUID SESSION_ID = UUID.fromString("00000000-0000-0000-0000-000000000103");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ChatAuditMapper mapper = new ChatAuditMapper(objectMapper, new ChatbotResponseMapper(objectMapper));

    private ChatAudit audit(String json) throws Exception {
        return mapper.toChatAudit(SESSION_ID, null, "BN2026-00001",
                objectMapper.readValue(json, ChatbotChatResponse.class), 5L);
    }

    @Test
    void classifiesPatientDataAccessByTool() throws Exception {
        ChatAudit audit = audit("""
                {"tool_name": "get_medication_requests", "patient_id": "BN2026-00007", "response_status": "answered"}
                """);

        assertEquals(AuditAction.VIEW_MEDICATIONS, audit.action());
        assertEquals(AuditResource.MEDICATION_REQUEST, audit.resource());
        assertEquals("BN2026-00007", audit.resourceId());
    }

    @Test
    void classifiesAllPatientAndSearchTools() throws Exception {
        assertEquals("all_patients", audit("{\"tool_name\": \"get_all_patient_conditions\"}").resourceId());
        assertEquals("search_query", audit("{\"tool_name\": \"search_patients\"}").resourceId());
        assertEquals("all_patients", audit("{\"tool_name\": \"multi_tool_plan\", \"all_patients\": true}").resourceId());
    }

    @Test
    void unknownToolIsAPlainChatTurnOnTheSession() throws Exception {
        ChatAudit audit = audit("{\"tool_name\": \"brand_new_tool\"}");

        assertEquals(AuditAction.CHAT_COMPLETED, audit.action());
        assertEquals(AuditResource.CHAT_SESSION, audit.resource());
        assertEquals(SESSION_ID.toString(), audit.resourceId());
    }

    @Test
    void patientToolWithoutPatientFallsBackToEffectivePatient() throws Exception {
        assertEquals("BN2026-00001", audit("{\"tool_name\": \"get_observations\", \"patient_id\": \" \"}").resourceId());
    }

    @Test
    void auditsTurnsWithoutDataByResponseStatusInsteadOfToolName() throws Exception {
        // The agent uses the same tool_name for refused questions and clarification questions.
        ChatAudit unsupported = audit("""
                {"tool_name": "unsupported_question", "response_status": "answered", "agent_route": "unsupported"}
                """);
        ChatAudit clarification = audit("""
                {"tool_name": "unsupported_question", "response_status": "clarification_needed", "agent_route": "fhir"}
                """);
        ChatAudit toolError = audit("""
                {"tool_name": "get_observations", "response_status": "tool_error", "agent_route": "fhir",
                 "patient_id": "BN2026-00001"}
                """);
        ChatAudit noData = audit("""
                {"tool_name": "get_observations", "response_status": "no_data", "agent_route": "fhir",
                 "patient_id": "BN2026-00001"}
                """);

        assertEquals(AuditAction.ASK_UNSUPPORTED, unsupported.action());
        assertEquals(AuditAction.CHAT_CLARIFICATION, clarification.action());
        assertEquals(AuditAction.CHAT_TOOL_ERROR, toolError.action());
        assertEquals(AuditResource.CHAT_SESSION, toolError.resource());
        assertEquals(SESSION_ID.toString(), toolError.resourceId());
        assertEquals("get_observations", toolError.metadata().path("tool_name").asText());
        // The query ran but found nothing: still an access to the patient record.
        assertEquals(AuditAction.VIEW_OBSERVATIONS, noData.action());
        assertEquals("BN2026-00001", noData.resourceId());
    }

    @Test
    void metadataRecordsTheIntentForAnalytics() throws Exception {
        ChatAudit audit = audit("""
                {"intent": "observations", "tool_name": "get_observations", "trace_id": "trace-1",
                 "plan_steps": [{"id": "s1", "tool": "get_observations"}],
                 "llm_calls": [{"stage": "agent_answer", "provider": "openai", "model": "m",
                                "input_tokens": 1, "output_tokens": 1}]}
                """);

        assertEquals("chat", audit.metadata().path("operation").asText());
        assertEquals("observations", audit.metadata().path("question_intent").asText());
        assertEquals("trace-1", audit.metadata().path("trace_id").asText());
        assertEquals(1, audit.metadata().path("llm_call_count").asInt());
        assertEquals("get_observations", audit.metadata().path("plan_steps").get(0).path("tool").asText());
    }
}
