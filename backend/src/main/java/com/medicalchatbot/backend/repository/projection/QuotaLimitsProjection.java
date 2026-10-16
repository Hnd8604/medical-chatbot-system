package com.medicalchatbot.backend.repository.projection;

import java.math.BigDecimal;

/** Daily limits of the quota policy assigned to a user. */
public record QuotaLimitsProjection(
        String policyName,
        int dailyRequestLimit,
        int dailyTokenLimit,
        BigDecimal dailyCostLimitUsd
) {
}
