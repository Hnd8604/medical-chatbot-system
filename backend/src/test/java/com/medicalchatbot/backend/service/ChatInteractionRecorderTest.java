package com.medicalchatbot.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medicalchatbot.backend.dto.chatbot.ChatbotChatResponse;
import com.medicalchatbot.backend.entity.AuditLog;
import com.medicalchatbot.backend.entity.ChatSession;
import com.medicalchatbot.backend.entity.LlmCallUsage;
import com.medicalchatbot.backend.entity.UsageLog;
import com.medicalchatbot.backend.entity.User;
import com.medicalchatbot.backend.enums.CostSource;
import com.medicalchatbot.backend.enums.UsageStatus;
import com.medicalchatbot.backend.enums.UserRole;
import com.medicalchatbot.backend.mapper.AuditLogMapper;
import com.medicalchatbot.backend.mapper.ChatAuditMapper;
import com.medicalchatbot.backend.mapper.ChatbotResponseMapper;
import com.medicalchatbot.backend.repository.AuditLogRepository;
import com.medicalchatbot.backend.repository.LlmCallUsageRepository;
import com.medicalchatbot.backend.repository.UsageLogRepository;
import com.medicalchatbot.backend.support.TestUsers;
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
    private final User user = TestUsers.user(UserRole.DOCTOR);
    private final ChatSession session = ChatSession.builder().id(UUID.randomUUID()).user(user).build();
    private ChatInteractionRecorder recorder;

    @BeforeEach
    void setUp() {
        ChatbotResponseMapper responseMapper = new ChatbotResponseMapper(objectMapper);
        recorder = new ChatInteractionRecorder(
                usageLogRepository,
                llmCallUsageRepository,
                new AuditService(auditLogRepository, new AuditLogMapper(), objectMapper),
                new ChatAuditMapper(objectMapper, responseMapper),
                objectMapper
        );
    }

    @Test
    void recordsGatewayCostPerCallAndAuditData() throws Exception {
        savedUsageIsReturned();

        recorder.recordCompletedTurn(user, session, "BN2026-request", "BN2026-effective", reply("""
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
                  "llm_calls": [
                    {"stage": "agent_router", "provider": "openai", "model": "gpt-4o-mini",
                     "input_tokens": 400, "output_tokens": 30, "cost_usd": 0.000078, "call_id": "c1"},
                    {"stage": "agent_answer", "provider": "openai", "model": "gpt-4.1-mini",
                     "input_tokens": 1000, "output_tokens": 200, "cost_usd": 0.000619, "call_id": "c2"}
                  ],
                  "plan_steps": [{"id": "s1", "tool": "get_observations"}]
                }
                """), 125L);

        UsageLog usageLog = capturedUsageLog();
        assertEquals(user, usageLog.getUser());
        assertEquals(UsageStatus.SUCCESS, usageLog.getStatus());
        assertEquals(1400, usageLog.getInputTokens());
        assertEquals(230, usageLog.getOutputTokens());
        assertEquals(new BigDecimal("0.000697"), usageLog.getEstimatedCostUsd());
        assertEquals(CostSource.GATEWAY, usageLog.getCostSource());
        assertEquals("trace-1", usageLog.getTraceId());
        assertEquals(125, usageLog.getLatencyMs());

        List<LlmCallUsage> calls = capturedCalls();
        assertEquals(List.of("agent_router", "agent_answer"), calls.stream().map(LlmCallUsage::getStage).toList());
        assertEquals(List.of("gpt-4o-mini", "gpt-4.1-mini"), calls.stream().map(LlmCallUsage::getLlmModel).toList());
        assertEquals(new BigDecimal("0.000078"), calls.get(0).getCostUsd());
        assertEquals("c1", calls.get(0).getGatewayCallId());
        assertEquals(usageLog, calls.get(0).getUsageLog());

        AuditLog audit = capturedAudit();
        assertEquals(session, audit.getSession());
        assertEquals("VIEW_OBSERVATIONS", audit.getAction());
        assertEquals("Observation", audit.getResourceType());
        assertEquals("BN2026-response", audit.getResourceId());
        assertEquals(125L, audit.getMetadataJson().path("latency_ms").asLong());
        assertEquals("BN2026-request", audit.getMetadataJson().path("request_patient_id").asText());
        assertEquals("BN2026-effective", audit.getMetadataJson().path("effective_patient_id").asText());
        assertEquals(2, audit.getMetadataJson().path("llm_call_count").asInt());
    }

    @Test
    void cacheHitSavedCostIsGatewayCostOfOriginalAnswer() throws Exception {
        savedUsageIsReturned();

        recordCompletedTurn("""
                {
                  "tool_name": "cache_hit",
                  "answer_source": "semantic_cache_strict",
                  "llm_calls": [],
                  "saved_usage": {"saved_input_tokens": 1000, "saved_output_tokens": 500, "saved_cost_usd": 0.0012}
                }
                """);

        UsageLog usageLog = capturedUsageLog();
        assertEquals(new BigDecimal("0.000000"), usageLog.getEstimatedCostUsd());
        assertEquals(CostSource.NONE, usageLog.getCostSource());
        assertEquals(1500, usageLog.getSavedTokens());
        assertEquals(new BigDecimal("0.001200"), usageLog.getSavedCostUsd());
        verify(llmCallUsageRepository, never()).saveAll(any());
    }

    @Test
    void cacheHitWithoutKnownCostSavesZero() throws Exception {
        savedUsageIsReturned();

        // Old cache entries were stored without the gateway cost.
        recordCompletedTurn("""
                {"tool_name": "cache_hit", "llm_calls": [],
                 "saved_usage": {"saved_input_tokens": 1000, "saved_output_tokens": 500, "saved_cost_usd": null}}
                """);

        assertEquals(BigDecimal.ZERO, capturedUsageLog().getSavedCostUsd());
    }

    @Test
    void callWithoutGatewayCostIsUnavailableNotEstimated() throws Exception {
        savedUsageIsReturned();

        recordCompletedTurn("""
                {"llm_calls": [
                   {"stage": "agent_router", "provider": "openai", "model": "gpt-4o-mini",
                    "input_tokens": 400, "output_tokens": 30, "cost_usd": 0.000078},
                   {"stage": "agent_answer", "provider": "openai", "model": "gpt-4.1-mini",
                    "input_tokens": 100, "output_tokens": 10, "cost_usd": null}
                ]}
                """);

        UsageLog usageLog = capturedUsageLog();
        assertEquals(new BigDecimal("0.000078"), usageLog.getEstimatedCostUsd());
        assertEquals(CostSource.UNAVAILABLE, usageLog.getCostSource());
        List<LlmCallUsage> calls = capturedCalls();
        assertEquals(CostSource.GATEWAY, calls.get(0).getCostSource());
        assertEquals(CostSource.UNAVAILABLE, calls.get(1).getCostSource());
        assertEquals(new BigDecimal("0.000000"), calls.get(1).getCostUsd());
    }

    @Test
    void gatewayZeroIsARealZero() throws Exception {
        savedUsageIsReturned();

        recordCompletedTurn("""
                {"llm_calls": [{"stage": "agent_router", "provider": "groq", "model": "llama-3.1-8b-instant",
                                "input_tokens": 50, "output_tokens": 5, "cost_usd": 0}]}
                """);

        assertEquals(CostSource.GATEWAY, capturedUsageLog().getCostSource());
    }

    @Test
    void failedTurnRecordsSpentCallsFromTheErrorBody() {
        savedUsageIsReturned();
        byte[] errorBody = """
                {"detail": "FHIR lỗi", "llm_calls": [{"stage": "agent_router", "provider": "openai",
                 "model": "gpt-4o-mini", "input_tokens": 300, "output_tokens": 20, "cost_usd": 0.000057,
                 "call_id": "c1"}]}
                """.getBytes(StandardCharsets.UTF_8);

        recorder.recordFailedTurn(user, null, 502, errorBody, 900L);

        UsageLog usageLog = capturedUsageLog();
        assertEquals(UsageStatus.FAILED, usageLog.getStatus());
        assertEquals("chatbot-service HTTP 502", usageLog.getErrorMessage());
        assertNull(usageLog.getSession());
        assertEquals(new BigDecimal("0.000057"), usageLog.getEstimatedCostUsd());
        assertEquals(CostSource.GATEWAY, usageLog.getCostSource());
        assertEquals(1, capturedCalls().size());

        AuditLog audit = capturedAudit();
        assertEquals("CHAT_FAILED", audit.getAction());
        assertEquals("chat_session", audit.getResourceType());
        assertNull(audit.getResourceId());
        assertEquals(502, audit.getMetadataJson().path("http_status").asInt());
    }

    @Test
    void failedTurnWithoutKnownCallsRecordsNothing() {
        recorder.recordFailedTurn(user, null, 502, "not json".getBytes(StandardCharsets.UTF_8), 900L);
        recorder.recordFailedTurn(user, null, 502, null, 900L);

        verifyNoInteractions(usageLogRepository, llmCallUsageRepository, auditLogRepository);
    }

    @Test
    void unsavedTurnRecordsTheCallsOfTheAnswer() throws Exception {
        savedUsageIsReturned();

        recorder.recordUnsavedTurn(user, session, reply("""
                {"llm_calls": [{"stage": "agent_answer", "provider": "openai", "model": "gpt-4.1-mini",
                                "input_tokens": 10, "output_tokens": 5, "cost_usd": 0.0001}]}
                """), 50L);

        UsageLog usageLog = capturedUsageLog();
        assertEquals(UsageStatus.FAILED, usageLog.getStatus());
        assertEquals(session, usageLog.getSession());
        assertEquals("backend error after chatbot-service answered", usageLog.getErrorMessage());
    }

    private void recordCompletedTurn(String json) throws Exception {
        recorder.recordCompletedTurn(user, session, null, null, reply(json), 10L);
    }

    private ChatbotChatResponse reply(String json) throws Exception {
        return objectMapper.readValue(json, ChatbotChatResponse.class);
    }

    private void savedUsageIsReturned() {
        when(usageLogRepository.save(any(UsageLog.class))).thenAnswer(invocation -> invocation.getArgument(0));
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

    private AuditLog capturedAudit() {
        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        return captor.getValue();
    }
}
