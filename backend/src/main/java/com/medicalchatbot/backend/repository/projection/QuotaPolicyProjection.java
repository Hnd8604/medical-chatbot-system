package com.medicalchatbot.backend.repository.projection;

import java.math.BigDecimal;

public record QuotaPolicyProjection(
        String policyName,
        int dailyRequestLimit,
        int dailyTokenLimit,
        BigDecimal dailyCostLimitUsd
) {
}
