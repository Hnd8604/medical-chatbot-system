package com.medicalchatbot.backend.repository.projection;

import java.math.BigDecimal;

public record QuotaUsageProjection(
        int usedRequests,
        int usedInputTokens,
        int usedOutputTokens,
        BigDecimal usedCostUsd
) {
    public int usedTokens() {
        return usedInputTokens + usedOutputTokens;
    }
}
