package com.medicalchatbot.backend.dto.response.analytics;

import java.math.BigDecimal;

/** Semantic-cache effectiveness: how many turns were answered from cache and what that saved. */
public record CacheMetricsResponse(
        long totalRequests,
        long totalCacheHits,
        long totalSavedTokens,
        BigDecimal totalSavedCostUsd,
        double hitRatePercentage
) {

    public static CacheMetricsResponse of(long totalRequests, long cacheHits, long savedTokens, BigDecimal savedCostUsd) {
        double hitRate = totalRequests == 0 ? 0.0 : cacheHits * 100.0 / totalRequests;
        return new CacheMetricsResponse(totalRequests, cacheHits, savedTokens, savedCostUsd, hitRate);
    }
}
