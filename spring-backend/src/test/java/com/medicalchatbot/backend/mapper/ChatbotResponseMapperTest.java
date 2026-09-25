package com.medicalchatbot.backend.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.medicalchatbot.backend.dto.response.ChatResponse;
import com.medicalchatbot.backend.dto.response.ChatSessionMemory;
import org.junit.jupiter.api.Test;

class ChatbotResponseMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ChatbotResponseMapper mapper = new ChatbotResponseMapper(objectMapper);

    @Test
    void mapsUpstreamResponseAndAssistantMetadata() throws Exception {
        UUID sessionId = UUID.fromString("00000000-0000-0000-0000-000000000101");
        UUID messageId = UUID.fromString("00000000-0000-0000-0000-000000000102");
        JsonNode upstream = objectMapper.readTree("""
                {
                  "answer": "Theo dữ liệu FHIR...",
                  "intent": "medications",
                  "tool_name": "get_medication_requests",
                  "answer_source": "llm",
                  "patient_id": "BN2026-00001",
                  "all_patients": false,
                  "needs_patient_selection": false,
                  "patient_candidates": [],
                  "evidence": [
                    {
                      "resource_type": "MedicationRequest",
                      "id": "med-1",
                      "summary": "Amlodipine"
                    }
                  ],
                  "memory_update": {},
                  "external_knowledge": [
                    {
                      "source": "MedlinePlus",
                      "type": "medication",
                      "system": "rxnorm",
                      "code": "10582",
                      "display": "Levothyroxine",
                      "url": "https://example.test/medicine"
                    }
                  ],
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
    void keepsPatientAndResourceContextForAllPatientResponse() throws Exception {
        ChatSessionMemory current = new ChatSessionMemory(
                "BN2026-00001",
                "Đã xem huyết áp.",
                "observations",
                "get_observations",
                "Observation",
                "obs-1"
        );
        JsonNode upstream = objectMapper.readTree("""
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

        ChatSessionMemory next = mapper.nextSessionMemory(current, "BN2026-99999", upstream);

        assertEquals("BN2026-00001", next.activePatientId());
        assertEquals("Observation", next.lastResourceType());
        assertEquals("obs-1", next.lastResourceId());
        assertEquals("list_patients", next.lastIntent());
        assertEquals("Đã xem danh sách bệnh nhân.", next.memorySummary());
    }

    @Test
    void preservesOptionalFieldSemanticsWhenFieldsAreAbsent() throws Exception {
        ChatResponse response = mapper.toResponse(
                UUID.randomUUID(),
                UUID.randomUUID(),
                objectMapper.readTree("{}")
        );

        assertEquals("", response.answer());
        assertNull(response.allPatients());
        assertNull(response.needsPatientSelection());
        assertTrue(response.patientSearch().isMissingNode());
        assertTrue(response.patientCandidates().isMissingNode());
        assertFalse(response.usage().isObject());
    }
}
