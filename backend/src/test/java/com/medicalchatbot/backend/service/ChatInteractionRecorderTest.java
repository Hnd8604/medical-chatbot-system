package com.medicalchatbot.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medicalchatbot.backend.dto.request.ChatRequest;
import com.medicalchatbot.backend.entity.ChatSession;
import com.medicalchatbot.backend.entity.LlmCallUsage;
import com.medicalchatbot.backend.entity.UsageLog;
import com.medicalchatbot.backend.entity.User;
import com.medicalchatbot.backend.mapper.ChatbotResponseMapper;
import com.medicalchatbot.backend.mapper.ChatbotResponseMapper.LlmCall;
import com.medicalchatbot.backend.repository.AuditLogRepository;
import com.medicalchatbot.backend.repository.LlmCallUsageRepository;
import com.medicalchatbot.backend.repository.UsageLogRepository;
import org.junit.jupiter.api.BeforeEach;
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
    private LlmCallUsageRepository llmCallUsageRepository;

    @Mock
    private AuditLogRepository auditLogRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ChatInteractionRecorder recorder;

    @BeforeEach
    void setUp() {
        recorder = new ChatInteractionRecorder(
                usageLogRepository,
                llmCallUsageRepository,
                auditLogRepository,
                new ChatbotResponseMapper(objectMapper),
                objectMapper
        );
    }

    private UsageLog capturedUsageLog() {
        ArgumentCaptor<UsageLog> captor = ArgumentCaptor.forClass(UsageLog.class);
        verify(usageLogRepository).save(captor.capture());
        return captor.getValue();
    }

    @SuppressWarnings("unchecked")
    private List<LlmCallUsage> capturedCalls() {
        ArgumentCaptor<List<LlmCallUsage>> captor = ArgumentCaptor.forClass(List.class);
        verify(llmCallUsageRepository).saveAll(captor.capture());
        return captor.getValue();
    }

    private void recordTurn(JsonNode upstream) {
        UUID sessionId = UUID.fromString("00000000-0000-0000-0000-000000000209");
        recorder.record(
                org.mockito.Mockito.mock(User.class),
                new ChatSession(sessionId),
                new ChatRequest(sessionId, null, "?"),
                null,
                upstream,
                10L
        );
    }

    @Test
    void recordsGatewayCostPerCallAndAuditData() throws Exception {
        when(usageLogRepository.save(any(UsageLog.class))).thenAnswer(invocation -> invocation.getArgument(0));
        UUID sessionId = UUID.fromString("00000000-0000-0000-0000-000000000201");
        User user = org.mockito.Mockito.mock(User.class);
        ChatSession session = new ChatSession(sessionId);
        ChatRequest request = new ChatRequest(sessionId, "BN2026-request", "Huyết áp gần nhất?");
        JsonNode upstream = objectMapper.readTree("""
                {
                  "trace_id": "trace-1",
                  "response_status": "answered",
                  "agent_route": "fhir",
                  "intent": "observations",
                  "tool_name": "get_observations",
                  "patient_id": "BN2026-response",
                  "answer_source": "llm",
                  "llm_provider": "openai",
                  "llm_model": "gpt-4.1-mini",
                  "usage": {"input_tokens": 1400, "output_tokens": 230, "cost_usd": 0.000697},
                  "llm_calls": [
                    {"stage": "agent_router", "provider": "openai", "model": "gpt-4o-mini",
                     "input_tokens": 400, "output_tokens": 30, "cost_usd": 0.000078, "call_id": "c1"},
                    {"stage": "agent_answer", "provider": "openai", "model": "gpt-4.1-mini",
                     "input_tokens": 1000, "output_tokens": 200, "cost_usd": 0.000619, "call_id": "c2"}
                  ],
                  "plan_steps": [{"id": "s1", "tool": "get_observations"}],
                  "memory_update": {},
                  "external_knowledge": []
                }
                """);

        recorder.record(user, session, request, "BN2026-effective", upstream, 125L);

        UsageLog usageLog = capturedUsageLog();
        assertEquals(user, usageLog.getUser());
        assertEquals("success", usageLog.getStatus());
        assertEquals(1400, usageLog.getInputTokens());
        assertEquals(230, usageLog.getOutputTokens());
        assertEquals(new BigDecimal("0.000697"), usageLog.getEstimatedCostUsd());
        assertEquals("gateway", usageLog.getCostSource());
        assertEquals("trace-1", usageLog.getTraceId());
        assertEquals(125, usageLog.getLatencyMs());

        List<LlmCallUsage> calls = capturedCalls();
        assertEquals(List.of("agent_router", "agent_answer"), calls.stream().map(LlmCallUsage::getStage).toList());
        assertEquals(List.of("gpt-4o-mini", "gpt-4.1-mini"), calls.stream().map(LlmCallUsage::getLlmModel).toList());
        assertEquals(new BigDecimal("0.000078"), calls.get(0).getCostUsd());
        assertEquals("c1", calls.get(0).getGatewayCallId());
        assertEquals(usageLog, calls.get(0).getUsageLog());

        ArgumentCaptor<JsonNode> metadata = ArgumentCaptor.forClass(JsonNode.class);
        verify(auditLogRepository).save(
                eq(user),
                eq(session),
                eq("VIEW_OBSERVATIONS"),
                eq("Observation"),
                eq("BN2026-response"),
                metadata.capture()
        );
        assertEquals(125L, metadata.getValue().path("latency_ms").asLong());
        assertEquals("BN2026-request", metadata.getValue().path("request_patient_id").asText());
        assertEquals("BN2026-effective", metadata.getValue().path("effective_patient_id").asText());
        assertEquals("trace-1", metadata.getValue().path("trace_id").asText());
        assertEquals("answered", metadata.getValue().path("response_status").asText());
        assertEquals(2, metadata.getValue().path("llm_call_count").asInt());
        assertEquals("get_observations", metadata.getValue().path("plan_steps").get(0).path("tool").asText());
    }

    @Test
    void cacheHitSavedCostIsGatewayCostOfOriginalAnswer() throws Exception {
        when(usageLogRepository.save(any(UsageLog.class))).thenAnswer(invocation -> invocation.getArgument(0));

        recordTurn(objectMapper.readTree("""
                {
                  "tool_name": "cache_hit",
                  "answer_source": "semantic_cache_strict",
                  "llm_provider": "openai",
                  "llm_model": "gpt-4.1-mini",
                  "usage": {"input_tokens": 0, "output_tokens": 0, "cost_usd": 0.0},
                  "llm_calls": [],
                  "saved_usage": {"saved_input_tokens": 1000, "saved_output_tokens": 500, "saved_cost_usd": 0.0012}
                }
                """));

        UsageLog usageLog = capturedUsageLog();
        assertEquals(new BigDecimal("0.000000"), usageLog.getEstimatedCostUsd());
        assertEquals("none", usageLog.getCostSource());
        assertEquals(1500, usageLog.getSavedTokens());
        assertEquals(new BigDecimal("0.001200"), usageLog.getSavedCostUsd());
        verify(llmCallUsageRepository, never()).saveAll(any());
    }

    @Test
    void cacheHitWithoutKnownCostSavesZero() throws Exception {
        when(usageLogRepository.save(any(UsageLog.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Entry cache cũ chưa lưu cost gateway.
        recordTurn(objectMapper.readTree("""
                {
                  "tool_name": "cache_hit",
                  "llm_calls": [],
                  "saved_usage": {"saved_input_tokens": 1000, "saved_output_tokens": 500, "saved_cost_usd": null}
                }
                """));

        assertEquals(BigDecimal.ZERO, capturedUsageLog().getSavedCostUsd());
    }

    @Test
    void callWithoutGatewayCostIsUnavailableNotEstimated() throws Exception {
        when(usageLogRepository.save(any(UsageLog.class))).thenAnswer(invocation -> invocation.getArgument(0));

        recordTurn(objectMapper.readTree("""
                {
                  "llm_provider": "openai",
                  "llm_model": "gpt-4.1-mini",
                  "llm_calls": [
                    {"stage": "agent_router", "provider": "openai", "model": "gpt-4o-mini",
                     "input_tokens": 400, "output_tokens": 30, "cost_usd": 0.000078},
                    {"stage": "agent_answer", "provider": "openai", "model": "gpt-4.1-mini",
                     "input_tokens": 100, "output_tokens": 10, "cost_usd": null}
                  ]
                }
                """));

        UsageLog usageLog = capturedUsageLog();
        assertEquals(new BigDecimal("0.000078"), usageLog.getEstimatedCostUsd());
        assertEquals("unavailable", usageLog.getCostSource());
        List<LlmCallUsage> calls = capturedCalls();
        assertEquals("gateway", calls.get(0).getCostSource());
        assertEquals("unavailable", calls.get(1).getCostSource());
        assertEquals(new BigDecimal("0.000000"), calls.get(1).getCostUsd());
    }

    @Test
    void gatewayZeroIsARealZero() throws Exception {
        when(usageLogRepository.save(any(UsageLog.class))).thenAnswer(invocation -> invocation.getArgument(0));

        recordTurn(objectMapper.readTree("""
                {"llm_calls": [{"stage": "agent_router", "provider": "groq", "model": "llama-3.1-8b-instant",
                                "input_tokens": 50, "output_tokens": 5, "cost_usd": 0}]}
                """));

        assertEquals("gateway", capturedUsageLog().getCostSource());
    }

    @Test
    void failedTurnRecordsSpentCallsWithoutNewSession() {
        when(usageLogRepository.save(any(UsageLog.class))).thenAnswer(invocation -> invocation.getArgument(0));
        User user = org.mockito.Mockito.mock(User.class);
        List<LlmCall> calls = List.of(new LlmCall(
                "agent_router", "openai", "gpt-4o-mini", 300, 20, new BigDecimal("0.000057"), "c1"));

        recorder.recordFailedTurn(user, null, 502, calls, 900L);

        UsageLog usageLog = capturedUsageLog();
        assertEquals("failed", usageLog.getStatus());
        assertEquals("chatbot-service HTTP 502", usageLog.getErrorMessage());
        assertNull(usageLog.getSession());
        assertEquals(new BigDecimal("0.000057"), usageLog.getEstimatedCostUsd());
        assertEquals("gateway", usageLog.getCostSource());
        assertEquals(1, capturedCalls().size());

        ArgumentCaptor<JsonNode> metadata = ArgumentCaptor.forClass(JsonNode.class);
        verify(auditLogRepository).save(eq(user), eq(null), eq("CHAT_FAILED"), eq("chat_session"), eq(null),
                metadata.capture());
        assertEquals(502, metadata.getValue().path("http_status").asInt());
    }

    @Test
    void failedTurnWithoutCallsRecordsNothing() {
        recorder.recordFailedTurn(org.mockito.Mockito.mock(User.class), null, 502, List.of(), 900L);

        verifyNoInteractions(usageLogRepository, llmCallUsageRepository, auditLogRepository);
    }
}
