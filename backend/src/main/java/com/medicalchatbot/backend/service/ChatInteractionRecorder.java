package com.medicalchatbot.backend.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.medicalchatbot.backend.dto.chatbot.ChatbotChatResponse;
import com.medicalchatbot.backend.dto.chatbot.ChatbotErrorBody;
import com.medicalchatbot.backend.dto.chatbot.ChatbotLlmCall;
import com.medicalchatbot.backend.entity.ChatSession;
import com.medicalchatbot.backend.entity.LlmCallUsage;
import com.medicalchatbot.backend.entity.UsageLog;
import com.medicalchatbot.backend.entity.User;
import com.medicalchatbot.backend.enums.AuditAction;
import com.medicalchatbot.backend.enums.AuditResource;
import com.medicalchatbot.backend.enums.CostSource;
import com.medicalchatbot.backend.enums.UsageStatus;
import com.medicalchatbot.backend.mapper.ChatAuditMapper;
import com.medicalchatbot.backend.mapper.ChatAuditMapper.ChatAudit;
import com.medicalchatbot.backend.repository.LlmCallUsageRepository;
import com.medicalchatbot.backend.repository.UsageLogRepository;
import com.medicalchatbot.backend.util.TextUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records the usage (for quotas and cost reports) and the audit entry of every chat turn.
 *
 * <p>The LLM gateway (LiteLLM) is the only source of cost: each call carries the cost the gateway
 * reported, and a cache hit carries the cost of the original answer as its saving. A call without
 * a reported cost is recorded as 0 with source {@link CostSource#UNAVAILABLE} — Spring keeps no
 * price table and never estimates.</p>
 */
@Service
@RequiredArgsConstructor
public class ChatInteractionRecorder {

    private static final int COST_SCALE = 6;
    private static final String FAILED_ANSWER_SOURCE = "error";
    private static final String DEFAULT_ANSWER_SOURCE = "llm";

    private final UsageLogRepository usageLogRepository;
    private final LlmCallUsageRepository llmCallUsageRepository;
    private final AuditService auditService;
    private final ChatAuditMapper chatAuditMapper;
    private final ObjectMapper objectMapper;

    /** Runs inside the chat transaction: usage and audit are only kept if the whole turn is. */
    public void recordCompletedTurn(
            User user,
            ChatSession session,
            String requestPatientId,
            String effectivePatientId,
            ChatbotChatResponse reply,
            long latencyMs
    ) {
        JsonNode savedUsage = reply.savedUsage();
        saveUsage(user, session, latencyMs, new TurnUsage(
                UsageStatus.SUCCESS,
                reply.llmProvider(),
                reply.llmModel(),
                TextUtils.firstNonBlank(reply.answerSource(), DEFAULT_ANSWER_SOURCE),
                reply.traceId(),
                null,
                reply.llmCalls(),
                savedTokens(savedUsage),
                savedCostUsd(savedUsage)
        ));

        ChatAudit audit = chatAuditMapper.toChatAudit(session.getId(), requestPatientId, effectivePatientId, reply, latencyMs);
        auditService.record(user, session, audit.action(), audit.resource(), audit.resourceId(), audit.metadata());
    }

    /**
     * Records the LLM calls a turn paid for before chatbot-service failed (it answers 403/5xx with
     * {@code llm_calls}). Runs in its own transaction because the chat transaction rolls back.
     *
     * @param existingSession session that existed before this turn, or null — never one created
     *                        in the failing chat transaction, because that one is rolled back
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailedTurn(User user, ChatSession existingSession, int httpStatus, byte[] errorBody, long latencyMs) {
        recordPaidCallsOfFailedTurn(user, existingSession, "chatbot-service HTTP " + httpStatus, httpStatus,
                llmCallsOf(errorBody), latencyMs);
    }

    /**
     * Records the LLM calls of a turn chatbot-service answered but Spring failed to store: the money
     * was spent even though the chat transaction rolls back. Same session rule as {@link #recordFailedTurn}.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordUnsavedTurn(User user, ChatSession existingSession, ChatbotChatResponse reply, long latencyMs) {
        recordPaidCallsOfFailedTurn(user, existingSession, "backend error after chatbot-service answered", null,
                reply.llmCalls(), latencyMs);
    }

    private void recordPaidCallsOfFailedTurn(
            User user,
            ChatSession existingSession,
            String errorMessage,
            Integer httpStatus,
            List<ChatbotLlmCall> calls,
            long latencyMs
    ) {
        if (calls.isEmpty()) {
            return;
        }
        ChatbotLlmCall lastCall = calls.get(calls.size() - 1);
        UsageLog usageLog = saveUsage(user, existingSession, latencyMs, new TurnUsage(
                UsageStatus.FAILED,
                lastCall.provider(),
                lastCall.model(),
                FAILED_ANSWER_SOURCE,
                null,
                errorMessage,
                calls,
                0,
                BigDecimal.ZERO
        ));

        ObjectNode metadata = auditService.metadata().put("operation", "chat");
        if (httpStatus != null) {
            metadata.put("http_status", httpStatus);
        }
        metadata.put("error", errorMessage)
                .put("latency_ms", latencyMs)
                .put("llm_call_count", calls.size())
                .put("cost_usd", usageLog.getEstimatedCostUsd())
                .put("cost_source", usageLog.getCostSource().value());
        auditService.record(user, existingSession, AuditAction.CHAT_FAILED, AuditResource.CHAT_SESSION,
                existingSession != null ? existingSession.getId().toString() : null, metadata);
    }

    private UsageLog saveUsage(User user, ChatSession session, long latencyMs, TurnUsage turn) {
        List<CallCost> costs = turn.calls().stream().map(ChatInteractionRecorder::costOf).toList();
        BigDecimal totalCost = costs.stream()
                .map(CallCost::costUsd)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(COST_SCALE, RoundingMode.HALF_UP);

        UsageLog usageLog = usageLogRepository.save(UsageLog.builder()
                .user(user)
                .session(session)
                .llmProvider(TextUtils.truncate(turn.provider(), 50))
                .llmModel(TextUtils.truncate(turn.model(), 100))
                .status(turn.status())
                .latencyMs((int) Math.min(Math.max(0, latencyMs), Integer.MAX_VALUE))
                .inputTokens(turn.calls().stream().mapToInt(ChatbotLlmCall::inputTokens).sum())
                .outputTokens(turn.calls().stream().mapToInt(ChatbotLlmCall::outputTokens).sum())
                .estimatedCostUsd(totalCost)
                .costSource(CostSource.aggregate(costs.stream().map(CallCost::source).toList()))
                .traceId(TextUtils.truncate(turn.traceId(), 64))
                .errorMessage(turn.errorMessage())
                .answerSource(TextUtils.truncate(turn.answerSource(), 50))
                .savedTokens(turn.savedTokens())
                .savedCostUsd(turn.savedCostUsd())
                .build());

        List<LlmCallUsage> callRows = new ArrayList<>(turn.calls().size());
        for (int i = 0; i < turn.calls().size(); i++) {
            ChatbotLlmCall call = turn.calls().get(i);
            callRows.add(LlmCallUsage.builder()
                    .usageLog(usageLog)
                    .stage(TextUtils.truncate(call.stage(), 50))
                    .llmProvider(TextUtils.truncate(call.provider(), 50))
                    .llmModel(TextUtils.truncate(call.model(), 100))
                    .inputTokens(call.inputTokens())
                    .outputTokens(call.outputTokens())
                    .costUsd(costs.get(i).costUsd())
                    .costSource(costs.get(i).source())
                    .gatewayCallId(TextUtils.truncate(call.callId(), 100))
                    .build());
        }
        if (!callRows.isEmpty()) {
            llmCallUsageRepository.saveAll(callRows);
        }
        return usageLog;
    }

    /** An unreadable error body means no known calls, not a failure of its own. */
    private List<ChatbotLlmCall> llmCallsOf(byte[] errorBody) {
        if (errorBody == null || errorBody.length == 0) {
            return List.of();
        }
        try {
            return objectMapper.readValue(errorBody, ChatbotErrorBody.class).llmCalls();
        } catch (IOException exception) {
            return List.of();
        }
    }

    /** The gateway cost of the call (a reported 0 is real); without one: 0 and {@code unavailable}. */
    private static CallCost costOf(ChatbotLlmCall call) {
        BigDecimal gatewayCost = call.costUsd();
        return gatewayCost == null
                ? new CallCost(BigDecimal.ZERO.setScale(COST_SCALE, RoundingMode.HALF_UP), CostSource.UNAVAILABLE)
                : new CallCost(gatewayCost.setScale(COST_SCALE, RoundingMode.HALF_UP), CostSource.GATEWAY);
    }

    /** Tokens a cache hit saved: those of the original answer. */
    private static int savedTokens(JsonNode savedUsage) {
        if (savedUsage == null) {
            return 0;
        }
        return savedUsage.path("saved_input_tokens").asInt(0) + savedUsage.path("saved_output_tokens").asInt(0);
    }

    /** Cost a cache hit saved: the gateway cost of the original answer when it was cached. */
    private static BigDecimal savedCostUsd(JsonNode savedUsage) {
        JsonNode cost = savedUsage == null ? null : savedUsage.get("saved_cost_usd");
        if (cost == null || !cost.isNumber() || cost.decimalValue().signum() < 0) {
            return BigDecimal.ZERO;
        }
        return cost.decimalValue().setScale(COST_SCALE, RoundingMode.HALF_UP);
    }

    private record CallCost(BigDecimal costUsd, CostSource source) {
    }

    private record TurnUsage(
            UsageStatus status,
            String provider,
            String model,
            String answerSource,
            String traceId,
            String errorMessage,
            List<ChatbotLlmCall> calls,
            int savedTokens,
            BigDecimal savedCostUsd
    ) {
    }
}
