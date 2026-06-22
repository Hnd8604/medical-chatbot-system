package com.medicalchatbot.backend.dto.request;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonProperty;

public record QuotaPolicyUpsertRequest(
        @NotBlank @Size(max = 100) String name,

        @JsonProperty("daily_request_limit")
        @NotNull @Positive Integer dailyRequestLimit,

        @JsonProperty("daily_token_limit")
        @NotNull @Positive Integer dailyTokenLimit,

        @JsonProperty("daily_cost_limit_usd")
        @NotNull @DecimalMin(value = "0.0", inclusive = false) BigDecimal dailyCostLimitUsd,

        @JsonProperty("rate_limit_per_minute")
        @Positive Integer rateLimitPerMinute
) {
}
