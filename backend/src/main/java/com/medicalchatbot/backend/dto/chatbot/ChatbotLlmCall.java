package com.medicalchatbot.backend.dto.chatbot;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * One LLM call chatbot-service made during a turn. {@code costUsd == null} means the gateway did
 * not report a cost, which is different from a real cost of 0.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatbotLlmCall(
        String stage,
        String provider,
        String model,
        int inputTokens,
        int outputTokens,
        BigDecimal costUsd,
        String callId
) {

    private static final String UNKNOWN_STAGE = "unknown";

    /** Defensive normalization: never record a negative token count or cost. */
    public ChatbotLlmCall {
        stage = stage == null || stage.isBlank() ? UNKNOWN_STAGE : stage;
        inputTokens = Math.max(0, inputTokens);
        outputTokens = Math.max(0, outputTokens);
        costUsd = costUsd != null && costUsd.signum() < 0 ? null : costUsd;
    }
}
