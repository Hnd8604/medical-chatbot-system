package com.medicalchatbot.backend.repository.projection;

import java.math.BigDecimal;

public record CostByModelProjection(
        String llmProvider,
        String llmModel,
        int requestCount,
        int inputTokens,
        int outputTokens,
        BigDecimal estimatedCostUsd
) {
    public int totalTokens() {
        return inputTokens + outputTokens;
    }
}
