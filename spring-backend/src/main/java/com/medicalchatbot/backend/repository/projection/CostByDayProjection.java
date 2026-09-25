package com.medicalchatbot.backend.repository.projection;

import java.math.BigDecimal;
import java.time.LocalDate;

public record CostByDayProjection(
        LocalDate date,
        int requestCount,
        int inputTokens,
        int outputTokens,
        BigDecimal estimatedCostUsd
) {
    public int totalTokens() {
        return inputTokens + outputTokens;
    }
}
