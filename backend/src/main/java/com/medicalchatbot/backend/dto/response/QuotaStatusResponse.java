package com.medicalchatbot.backend.dto.response;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonProperty;

public record QuotaStatusResponse(
        String user,

        String policy,

        @JsonProperty("daily_request_limit")
        int dailyRequestLimit,

        @JsonProperty("daily_token_limit")
        int dailyTokenLimit,

        @JsonProperty("daily_cost_limit_usd")
        BigDecimal dailyCostLimitUsd,

        @JsonProperty("used_requests")
        int usedRequests,

        @JsonProperty("used_input_tokens")
        int usedInputTokens,

        @JsonProperty("used_output_tokens")
        int usedOutputTokens,

        @JsonProperty("used_tokens")
        int usedTokens,

        @JsonProperty("used_cost_usd")
        BigDecimal usedCostUsd,

        @JsonProperty("remaining_requests")
        int remainingRequests,

        @JsonProperty("remaining_tokens")
        int remainingTokens,

        @JsonProperty("remaining_cost_usd")
        BigDecimal remainingCostUsd,

        boolean allowed,

        @JsonProperty("blocked_reason")
        String blockedReason
) {
}
