package com.medicalchatbot.backend.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.medicalchatbot.backend.dto.chatbot.ChatbotChatResponse;
import com.medicalchatbot.backend.dto.response.chat.ChatResponse;
import com.medicalchatbot.backend.entity.ChatMemory;
import org.junit.jupiter.api.Test;

class ChatbotResponseMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ChatbotResponseMapper mapper = new ChatbotResponseMapper(objectMapper);

    private ChatbotChatResponse reply(String json) throws Exception {
        return objectMapper.readValue(json, ChatbotChatResponse.class);
    }

    @Test
    void readsTheUpstreamContractIncludingUnknownFields() throws Exception {
        ChatbotChatResponse reply = reply("""
                {"answer": "ok", "tool_name": "get_observations", "some_future_field": 1,
                 "llm_calls": [{"stage": "", "provider": "openai", "model": "m", "input_tokens": -5,
                                "output_tokens": 3, "cost_usd": 0.000078}]}
                """);

        assertEquals("get_observations", reply.toolName());
        assertEquals("unknown", reply.llmCalls().getFirst().stage());
        assertEquals(0, reply.llmCalls().getFirst().inputTokens());
        assertEquals(new BigDecimal("0.000078"), reply.llmCalls().getFirst().costUsd());
    }

    @Test
    void mapsUpstreamResponseAndAssistantMetadata() throws Exception {
        UUID sessionId = UUID.fromString("00000000-0000-0000-0000-000000000101");
        UUID messageId = UUID.fromString("00000000-0000-0000-0000-000000000102");
        ChatbotChatResponse upstream = reply("""
                {
                  "answer": "Theo dữ liệu FHIR...",
                  "intent": "medications",
                  "tool_name": "get_medication_requests",
                  "answer_source": "llm",
                  "patient_id": "BN2026-00001",
                  "all_patients": false,
                  "needs_patient_selection": false,
                  "patient_candidates": [],
                  "evidence": [{"resource_type": "MedicationRequest", "id": "med-1", "summary": "Amlodipine"}],
                  "memory_update": {},
                  "external_knowledge": [{"source": "MedlinePlus", "type": "medication", "system": "rxnorm",
                                          "code": "10582", "display": "Levothyroxine",
                                          "url": "https://example.test/medicine"}],
                  "usage": {"input_tokens": 12, "output_tokens": 8}
                }
                """);

        ChatResponse response = mapper.toResponse(sessionId, messageId, upstream);
        ObjectNode metadata = mapper.assistantMessageMetadata(upstream);

        assertEquals(sessionId, response.sessionId());
        assertEquals(messageId, response.messageId());
        assertEquals("Theo dữ liệu FHIR...", response.answer());
        assertEquals("get_medication_requests", response.toolName());
        assertEquals(Boolean.FALSE, response.allPatients());
        assertEquals(Boolean.FALSE, response.needsPatientSelection());
        assertEquals("med-1", metadata.path("evidence_refs").get(0).path("resource_id").asText());
        assertEquals("MedlinePlus", metadata.path("knowledge_refs").get(0).path("source").asText());
    }

    @Test
    void assistantMetadataKeepsTurnOutcomeForHistory() throws Exception {
        ObjectNode metadata = mapper.assistantMessageMetadata(reply("""
                {
                  "answer": "Glucose 7,2 mmol/L.",
                  "answer_source": "template_grounding_fallback",
                  "response_status": "partial",
                  "trace_id": "trace-7",
                  "grounding": {"checked": true, "passed": false},
                  "llm_unavailable_reason": null
                }
                """));

        assertEquals("partial", metadata.path("response_status").asText());
        assertEquals("trace-7", metadata.path("trace_id").asText());
        assertTrue(metadata.path("grounding").path("checked").asBoolean());
        assertFalse(metadata.path("grounding").path("passed").asBoolean(true));
        assertTrue(metadata.path("llm_unavailable_reason").isNull());
    }

    @Test
    void keepsPatientAndResourceContextForAllPatientResponse() throws Exception {
        ChatMemory current = new ChatMemory("BN2026-00001", "Đã xem huyết áp.", "observations",
                "get_observations", "Observation", "obs-1");
        ChatbotChatResponse upstream = reply("""
                {
                  "intent": "list_patients",
                  "tool_name": "search_patients",
                  "all_patients": true,
                  "patient_id": "BN2026-99999",
                  "memory_update": {
                    "active_patient_id": "BN2026-99999",
                    "last_intent": "list_patients",
                    "last_tool_name": "search_patients",
                    "last_resource_type": "Patient",
                    "last_resource_id": "BN2026-99999",
                    "summary": "Đã xem danh sách bệnh nhân."
                  }
                }
                """);

        ChatMemory next = mapper.nextMemory(current, "BN2026-99999", upstream);

        assertEquals("BN2026-00001", next.activePatientId());
        assertEquals("Observation", next.lastResourceType());
        assertEquals("obs-1", next.lastResourceId());
        assertEquals("list_patients", next.lastIntent());
        assertEquals("Đã xem danh sách bệnh nhân.", next.summary());
    }

    @Test
    void movesFocusToTheAnsweredPatientAndItsFirstEvidence() throws Exception {
        ChatbotChatResponse upstream = reply("""
                {"intent": "observations", "tool_name": "get_observations", "patient_id": "BN2026-00002",
                 "evidence": [{"resource_type": "Observation", "id": "obs-9"}]}
                """);

        ChatMemory next = mapper.nextMemory(ChatMemory.EMPTY, "BN2026-00002", upstream);

        assertEquals("BN2026-00002", next.activePatientId());
        assertEquals("Observation", next.lastResourceType());
        assertEquals("obs-9", next.lastResourceId());
        assertEquals("get_observations", next.lastToolName());
    }

    @Test
    void buildsToolErrorAlertMetadata() throws Exception {
        ObjectNode metadata = mapper.toolErrorAlertMetadata(reply("""
                {"response_status": "tool_error", "trace_id": "trace-9", "tool_name": "multi_tool_plan",
                 "failed_steps": [{"id": "m", "tool": "get_medication_requests", "error_code": "fhir_error"}]}
                """));

        assertEquals("trace-9", metadata.path("trace_id").asText());
        assertEquals("get_medication_requests", metadata.path("failed_steps").get(0).path("tool").asText());
    }

    @Test
    void absentFieldsStayAbsentInTheResponse() throws Exception {
        ChatResponse response = mapper.toResponse(UUID.randomUUID(), UUID.randomUUID(), reply("{}"));

        assertEquals("", response.answer());
        assertNull(response.allPatients());
        assertNull(response.needsPatientSelection());
        assertNull(response.patientSearch());
        assertNull(response.patientCandidates());
        assertNull(response.usage());
    }
}
