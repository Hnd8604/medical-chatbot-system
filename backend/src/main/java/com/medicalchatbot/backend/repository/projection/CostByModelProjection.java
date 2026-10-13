package com.medicalchatbot.backend.repository.projection;

import java.math.BigDecimal;

public record CostByModelProjection(
        String llmProvider,
        String llmModel,
        int callCount,
        int inputTokens,
        int outputTokens,
        BigDecimal costUsd
) {
    public int totalTokens() {
        return inputTokens + outputTokens;
    }
}
