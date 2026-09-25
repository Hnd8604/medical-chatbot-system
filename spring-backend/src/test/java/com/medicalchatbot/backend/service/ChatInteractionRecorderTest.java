package com.medicalchatbot.backend.service;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medicalchatbot.backend.dto.request.ChatRequest;
import com.medicalchatbot.backend.entity.ChatSession;
import com.medicalchatbot.backend.entity.User;
import com.medicalchatbot.backend.mapper.ChatbotResponseMapper;
import com.medicalchatbot.backend.repository.AuditLogRepository;
import com.medicalchatbot.backend.repository.UsageLogRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ChatInteractionRecorderTest {

    @Mock
    private UsageLogRepository usageLogRepository;

    @Mock
    private AuditLogRepository auditLogRepository;

    @Mock
    private CostEstimationService costEstimationService;

    @Test
    void recordsMappedUsageAndAuditData() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        ChatbotResponseMapper responseMapper = new ChatbotResponseMapper(objectMapper);
        ChatInteractionRecorder recorder = new ChatInteractionRecorder(
                usageLogRepository,
                auditLogRepository,
                costEstimationService,
                responseMapper
        );
        UUID sessionId = UUID.fromString("00000000-0000-0000-0000-000000000201");
        User user = org.mockito.Mockito.mock(User.class);
        ChatSession session = new ChatSession(sessionId);
        ChatRequest request = new ChatRequest(sessionId, "BN2026-request", "Huyết áp gần nhất?");
        JsonNode upstream = objectMapper.readTree("""
                {
                  "intent": "observations",
                  "tool_name": "get_observations",
                  "patient_id": "BN2026-response",
                  "answer_source": "semantic_cache",
                  "llm_provider": "openai",
                  "llm_model": "gpt-4.1-mini",
                  "usage": {
                    "input_tokens": 100,
                    "output_tokens": 40,
                    "estimated_cost_usd": "0.004"
                  },
                  "saved_usage": {
                    "saved_input_tokens": 30,
                    "saved_output_tokens": 10
                  },
                  "memory_update": {},
                  "external_knowledge": []
                }
                """);
        when(costEstimationService.estimateUsd(
                "openai",
                "gpt-4.1-mini",
                100,
                40,
                new BigDecimal("0.004")
        )).thenReturn(new BigDecimal("0.005"));
        when(costEstimationService.estimateUsd(
                "openai",
                "gpt-4.1-mini",
                30,
                10,
                BigDecimal.ZERO
        )).thenReturn(new BigDecimal("0.001"));

        recorder.record(user, session, request, "BN2026-effective", upstream, 125L);

        verify(usageLogRepository).save(
                eq(user),
                eq(session),
                eq("openai"),
                eq("gpt-4.1-mini"),
                eq("chat"),
                eq("success"),
                eq(125L),
                eq(100),
                eq(40),
                eq(new BigDecimal("0.005")),
                isNull(),
                eq("semantic_cache"),
                eq(40),
                eq(new BigDecimal("0.001"))
        );

        ArgumentCaptor<JsonNode> metadata = ArgumentCaptor.forClass(JsonNode.class);
        verify(auditLogRepository).save(
                eq(user),
                eq(session),
                eq("VIEW_OBSERVATIONS"),
                eq("Observation"),
                eq("BN2026-response"),
                metadata.capture()
        );
        org.junit.jupiter.api.Assertions.assertEquals(125L, metadata.getValue().path("latency_ms").asLong());
        org.junit.jupiter.api.Assertions.assertEquals(
                "BN2026-request",
                metadata.getValue().path("request_patient_id").asText()
        );
        org.junit.jupiter.api.Assertions.assertEquals(
                "BN2026-effective",
                metadata.getValue().path("effective_patient_id").asText()
        );
    }
}
