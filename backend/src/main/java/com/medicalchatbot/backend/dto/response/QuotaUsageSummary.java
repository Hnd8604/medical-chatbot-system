package com.medicalchatbot.backend.dto.response;

import java.math.BigDecimal;

public record QuotaUsageSummary(
        int usedRequests,

        int usedInputTokens,

        int usedOutputTokens,

        BigDecimal usedCostUsd
) {
    public int usedTokens() {
        return usedInputTokens + usedOutputTokens;
    }
}
