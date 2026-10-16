package com.medicalchatbot.backend.mapper;

import com.medicalchatbot.backend.dto.request.QuotaPolicyRequest;
import com.medicalchatbot.backend.dto.response.QuotaPolicyResponse;
import com.medicalchatbot.backend.entity.QuotaPolicy;
import org.springframework.stereotype.Component;

@Component
public class QuotaPolicyMapper {

    public QuotaPolicyResponse toResponse(QuotaPolicy policy) {
        return new QuotaPolicyResponse(
                policy.getId(),
                policy.getName(),
                policy.getDailyRequestLimit(),
                policy.getDailyTokenLimit(),
                policy.getDailyCostLimitUsd(),
                policy.getRateLimitPerMinute(),
                policy.getCreatedAt()
        );
    }

    public void applyRequest(QuotaPolicyRequest request, QuotaPolicy policy) {
        policy.setName(request.name());
        policy.setDailyRequestLimit(request.dailyRequestLimit());
        policy.setDailyTokenLimit(request.dailyTokenLimit());
        policy.setDailyCostLimitUsd(request.dailyCostLimitUsd());
        policy.setRateLimitPerMinute(request.rateLimitPerMinute());
    }
}
