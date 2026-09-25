package com.medicalchatbot.backend.dto.response;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

import com.medicalchatbot.backend.entity.QuotaPolicy;

public record QuotaPolicyAdminResponse(
        UUID id,
        String name,

        @JsonProperty("daily_request_limit")
        int dailyRequestLimit,

        @JsonProperty("daily_token_limit")
        int dailyTokenLimit,

        @JsonProperty("daily_cost_limit_usd")
        BigDecimal dailyCostLimitUsd,

        @JsonProperty("rate_limit_per_minute")
        Integer rateLimitPerMinute,

        @JsonProperty("created_at")
        OffsetDateTime createdAt
) {
    public static QuotaPolicyAdminResponse from(QuotaPolicy policy) {
        return new QuotaPolicyAdminResponse(
                policy.getId(),
                policy.getName(),
                policy.getDailyRequestLimit(),
                policy.getDailyTokenLimit(),
                policy.getDailyCostLimitUsd(),
                policy.getRateLimitPerMinute(),
                policy.getCreatedAt()
        );
    }
}
