package com.medicalchatbot.backend.dto.response;

import java.math.BigDecimal;

public record QuotaPolicyInfo(
        String policyName,

        int dailyRequestLimit,

        int dailyTokenLimit,

        BigDecimal dailyCostLimitUsd
) {
}
