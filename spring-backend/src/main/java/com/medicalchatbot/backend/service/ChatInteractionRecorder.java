package com.medicalchatbot.backend.service;

import java.math.BigDecimal;

import com.fasterxml.jackson.databind.JsonNode;
import com.medicalchatbot.backend.dto.request.ChatRequest;
import com.medicalchatbot.backend.entity.ChatSession;
import com.medicalchatbot.backend.entity.User;
import com.medicalchatbot.backend.mapper.ChatbotResponseMapper;
import com.medicalchatbot.backend.mapper.ChatbotResponseMapper.ChatAudit;
import com.medicalchatbot.backend.mapper.ChatbotResponseMapper.ChatUsage;
import com.medicalchatbot.backend.repository.AuditLogRepository;
import com.medicalchatbot.backend.repository.UsageLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Records the usage and audit side effects of a completed chat interaction.
 *
 * <p>This component intentionally declares no transaction boundary. Calls from
 * {@link ChatApplicationService#chat(ChatRequest)} therefore remain part of that method's transaction.</p>
 */
@Service
@RequiredArgsConstructor
public class ChatInteractionRecorder {

    private final UsageLogRepository usageLogRepository;
    private final AuditLogRepository auditLogRepository;
    private final CostEstimationService costEstimationService;
    private final ChatbotResponseMapper chatbotResponseMapper;

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

    private void recordUsage(User user, ChatSession session, JsonNode chatbotResponse, long latencyMs) {
        ChatUsage usage = chatbotResponseMapper.usage(chatbotResponse);
        BigDecimal estimatedCostUsd = costEstimationService.estimateUsd(
                usage.llmProvider(),
                usage.llmModel(),
                usage.inputTokens(),
                usage.outputTokens(),
                usage.reportedEstimatedCostUsd()
        );

        BigDecimal savedCostUsd = BigDecimal.ZERO;
        if (usage.savedUsagePresent()) {
            savedCostUsd = costEstimationService.estimateUsd(
                    usage.llmProvider(),
                    usage.llmModel(),
                    usage.savedInputTokens(),
                    usage.savedOutputTokens(),
                    BigDecimal.ZERO
            );
        }

        usageLogRepository.save(
                user,
                session,
                usage.llmProvider(),
                usage.llmModel(),
                "chat",
                "success",
                latencyMs,
                usage.inputTokens(),
                usage.outputTokens(),
                estimatedCostUsd,
                null,
                usage.answerSource(),
                usage.savedTokens(),
                savedCostUsd
        );
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
}
