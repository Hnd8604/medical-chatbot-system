package com.medicalchatbot.backend.dto.response.admin;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record QuotaPolicyResponse(
        UUID id,
        String name,
        int dailyRequestLimit,
        int dailyTokenLimit,
        BigDecimal dailyCostLimitUsd,
        Integer rateLimitPerMinute,
        OffsetDateTime createdAt
) {
}
