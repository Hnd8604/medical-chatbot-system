package com.medicalchatbot.backend.dto.request;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** Create or replace a quota policy; {@code rateLimitPerMinute} is optional. */
public record QuotaPolicyRequest(
        @NotBlank @Size(max = 100) String name,
        @NotNull @Positive Integer dailyRequestLimit,
        @NotNull @Positive Integer dailyTokenLimit,
        @NotNull @DecimalMin(value = "0.0", inclusive = false) BigDecimal dailyCostLimitUsd,
        @Positive Integer rateLimitPerMinute
) {
}
