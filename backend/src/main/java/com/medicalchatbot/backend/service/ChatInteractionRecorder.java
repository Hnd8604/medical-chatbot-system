package com.medicalchatbot.backend.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.medicalchatbot.backend.dto.request.ChatRequest;
import com.medicalchatbot.backend.entity.ChatSession;
import com.medicalchatbot.backend.entity.LlmCallUsage;
import com.medicalchatbot.backend.entity.UsageLog;
import com.medicalchatbot.backend.entity.User;
import com.medicalchatbot.backend.enums.CostSource;
import com.medicalchatbot.backend.mapper.ChatbotResponseMapper;
import com.medicalchatbot.backend.mapper.ChatbotResponseMapper.ChatAudit;
import com.medicalchatbot.backend.mapper.ChatbotResponseMapper.ChatUsage;
import com.medicalchatbot.backend.mapper.ChatbotResponseMapper.LlmCall;
import com.medicalchatbot.backend.repository.AuditLogRepository;
import com.medicalchatbot.backend.repository.LlmCallUsageRepository;
import com.medicalchatbot.backend.repository.UsageLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records the usage and audit side effects of a chat interaction.
 *
 * <p>{@link #record} intentionally declares no transaction boundary, so calls from
 * {@link ChatApplicationService#chat(ChatRequest)} remain part of that method's transaction.
 * {@link #recordFailedTurn} and {@link #recordUnsavedTurn} run in their own transaction because
 * the chat transaction is rolled back when the turn fails, while the money already spent must
 * still be kept.</p>
 *
 * <p>The gateway (LiteLLM) is the only source of cost: every LLM call carries the cost the
 * gateway reported, and a cache hit carries the gateway cost of the original answer as its
 * saved cost. A call without a gateway cost is recorded as 0 with source
 * {@code unavailable} — Spring keeps no price table and never estimates.</p>
 */
@Service
@RequiredArgsConstructor
public class ChatInteractionRecorder {

    private static final int COST_SCALE = 6;
    private static final String STATUS_SUCCESS = "success";
    private static final String STATUS_FAILED = "failed";

    private final UsageLogRepository usageLogRepository;
    private final LlmCallUsageRepository llmCallUsageRepository;
    private final AuditLogRepository auditLogRepository;
    private final ChatbotResponseMapper chatbotResponseMapper;
    private final ObjectMapper objectMapper;

    public void record(
            User user,
            ChatSession session,
            ChatRequest request,
            String effectivePatientId,
            JsonNode chatbotResponse,
            long latencyMs
    ) {
        recordUsage(user, session, chatbotResponse, latencyMs);
        recordAudit(user, session, request, effectivePatientId, chatbotResponse, latencyMs);
    }

    /**
     * Ghi các LLM call đã tốn tiền của một lượt hỏng giữa pipeline (chatbot-service trả
     * 403/5xx kèm {@code llm_calls}). Không có call nào thì không ghi gì.
     *
     * @param existingSession session đã tồn tại trước lượt này, hoặc null. Không được truyền
     *                        session vừa tạo trong transaction chat — nó sẽ bị rollback.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailedTurn(
            User user,
            ChatSession existingSession,
            int httpStatus,
            List<LlmCall> calls,
            long latencyMs
    ) {
        saveFailedTurn(user, existingSession, "chatbot-service HTTP " + httpStatus, httpStatus, calls, latencyMs);
    }

    /**
     * Ghi các LLM call của một lượt mà chatbot-service đã trả lời xong nhưng Spring lỗi khi lưu
     * (transaction chat rollback): tiền đã tiêu vẫn phải vào sổ. Quy ước session như
     * {@link #recordFailedTurn}.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordUnsavedTurn(
            User user,
            ChatSession existingSession,
            JsonNode chatbotResponse,
            long latencyMs
    ) {
        saveFailedTurn(
                user,
                existingSession,
                "backend error after chatbot-service answered",
                null,
                chatbotResponseMapper.usage(chatbotResponse).calls(),
                latencyMs
        );
    }

    private void saveFailedTurn(
            User user,
            ChatSession existingSession,
            String errorMessage,
            Integer httpStatus,
            List<LlmCall> calls,
            long latencyMs
    ) {
        if (calls.isEmpty()) {
            return;
        }
        LlmCall lastCall = calls.get(calls.size() - 1);
        UsageLog usageLog = saveUsage(
                user,
                existingSession,
                lastCall.provider(),
                lastCall.model(),
                STATUS_FAILED,
                errorMessage,
                latencyMs,
                "error",
                null,
                calls,
                0,
                BigDecimal.ZERO
        );

        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("operation", "chat");
        if (httpStatus != null) {
            metadata.put("http_status", httpStatus);
        }
        metadata.put("error", errorMessage);
        metadata.put("latency_ms", latencyMs);
        metadata.put("llm_call_count", calls.size());
        metadata.put("cost_usd", usageLog.getEstimatedCostUsd());
        metadata.put("cost_source", usageLog.getCostSource());
        auditLogRepository.save(
                user,
                existingSession,
                "CHAT_FAILED",
                "chat_session",
                existingSession != null ? existingSession.getId().toString() : null,
                metadata
        );
    }

    private void recordUsage(User user, ChatSession session, JsonNode chatbotResponse, long latencyMs) {
        ChatUsage usage = chatbotResponseMapper.usage(chatbotResponse);

        // Cache hit: tiết kiệm đúng bằng cost gateway của câu trả lời gốc lúc ghi cache.
        BigDecimal savedCostUsd = usage.savedCostUsd() != null
                ? usage.savedCostUsd().setScale(COST_SCALE, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        saveUsage(
                user,
                session,
                usage.llmProvider(),
                usage.llmModel(),
                STATUS_SUCCESS,
                null,
                latencyMs,
                usage.answerSource(),
                usage.traceId(),
                usage.calls(),
                usage.savedTokens(),
                savedCostUsd
        );
    }

    private UsageLog saveUsage(
            User user,
            ChatSession session,
            String llmProvider,
            String llmModel,
            String status,
            String errorMessage,
            long latencyMs,
            String answerSource,
            String traceId,
            List<LlmCall> calls,
            int savedTokens,
            BigDecimal savedCostUsd
    ) {
        List<CallCost> costs = calls.stream().map(ChatInteractionRecorder::callCost).toList();
        BigDecimal totalCostUsd = costs.stream()
                .map(CallCost::costUsd)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(COST_SCALE, RoundingMode.HALF_UP);

        UsageLog usageLog = new UsageLog(
                user,
                session,
                truncate(llmProvider, 50),
                truncate(llmModel, 100),
                "chat",
                status,
                latencyMs,
                calls.stream().mapToInt(LlmCall::inputTokens).sum(),
                calls.stream().mapToInt(LlmCall::outputTokens).sum(),
                totalCostUsd,
                errorMessage,
                truncate(answerSource, 50),
                savedTokens,
                savedCostUsd
        );
        usageLog.setCostSource(CostSource.aggregate(costs.stream().map(CallCost::source).toList()).value());
        usageLog.setTraceId(truncate(traceId, 64));
        UsageLog saved = usageLogRepository.save(usageLog);

        List<LlmCallUsage> rows = new ArrayList<>(calls.size());
        for (int i = 0; i < calls.size(); i++) {
            LlmCall call = calls.get(i);
            CallCost cost = costs.get(i);
            rows.add(LlmCallUsage.builder()
                    .usageLog(saved)
                    .stage(truncate(call.stage(), 50))
                    .llmProvider(truncate(call.provider(), 50))
                    .llmModel(truncate(call.model(), 100))
                    .inputTokens(call.inputTokens())
                    .outputTokens(call.outputTokens())
                    .costUsd(cost.costUsd())
                    .costSource(cost.source().value())
                    .gatewayCallId(truncate(call.callId(), 100))
                    .build());
        }
        if (!rows.isEmpty()) {
            llmCallUsageRepository.saveAll(rows);
        }
        return saved;
    }

    private void recordAudit(
            User user,
            ChatSession session,
            ChatRequest request,
            String effectivePatientId,
            JsonNode chatbotResponse,
            long latencyMs
    ) {
        ChatAudit audit = chatbotResponseMapper.audit(
                session.getId(),
                request,
                effectivePatientId,
                chatbotResponse,
                latencyMs
        );
        auditLogRepository.save(
                user,
                session,
                audit.action(),
                audit.resourceType(),
                audit.resourceId(),
                audit.metadata()
        );
    }

    private record CallCost(BigDecimal costUsd, CostSource source) {
    }

    /** Cost gateway của call (kể cả 0 — gateway báo thật là 0); không có thì 0 + unavailable. */
    private static CallCost callCost(LlmCall call) {
        BigDecimal gatewayCost = call.gatewayCostUsd();
        if (gatewayCost == null) {
            return new CallCost(BigDecimal.ZERO.setScale(COST_SCALE, RoundingMode.HALF_UP), CostSource.UNAVAILABLE);
        }
        return new CallCost(gatewayCost.setScale(COST_SCALE, RoundingMode.HALF_UP), CostSource.GATEWAY);
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
