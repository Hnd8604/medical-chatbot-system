package com.medicalchatbot.backend.repository.projection;

import java.math.BigDecimal;

/**
 * Usage of one user over a time window. {@code requestCount} only counts successful turns, while
 * tokens and cost also include failed turns because they were still paid for.
 */
public record UsageTotalsProjection(
        int requestCount,
        int inputTokens,
        int outputTokens,
        BigDecimal costUsd
) {
    public int totalTokens() {
        return inputTokens + outputTokens;
    }
}
