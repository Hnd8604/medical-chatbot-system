package com.medicalchatbot.backend.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

public record CacheMetricsResponse(
        @JsonProperty("total_requests")
        Long totalRequests,

        @JsonProperty("total_cache_hits")
        Long totalCacheHits,

        @JsonProperty("total_saved_tokens")
        Long totalSavedTokens,

        @JsonProperty("total_saved_cost_usd")
        BigDecimal totalSavedCostUsd
) {

    public CacheMetricsResponse(Number totalRequests, Number totalCacheHits, Number totalSavedTokens, Number totalSavedCostUsd) {
        this(
                totalRequests == null ? 0L : totalRequests.longValue(),
                totalCacheHits == null ? 0L : totalCacheHits.longValue(),
                totalSavedTokens == null ? 0L : totalSavedTokens.longValue(),
                totalSavedCostUsd == null ? BigDecimal.ZERO : new BigDecimal(totalSavedCostUsd.toString())
        );
    }

    @JsonProperty("hit_rate_percentage")
    public Double getHitRatePercentage() {
        if (totalRequests == null || totalRequests == 0) {
            return 0.0;
        }
        return (totalCacheHits * 100.0) / totalRequests;
    }
}