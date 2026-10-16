package com.medicalchatbot.backend.dto.response.usage;

import java.math.BigDecimal;

/** Today's usage of one user against the daily limits of their quota policy. */
public record QuotaStatusResponse(
        String user,
        String policy,
        int dailyRequestLimit,
        int dailyTokenLimit,
        BigDecimal dailyCostLimitUsd,
        int usedRequests,
        int usedInputTokens,
        int usedOutputTokens,
        int usedTokens,
        BigDecimal usedCostUsd,
        int remainingRequests,
        int remainingTokens,
        BigDecimal remainingCostUsd,
        boolean allowed,
        String blockedReason
) {
}
