package com.medicalchatbot.backend.repository.projection;

import java.math.BigDecimal;

public record CacheMetricsProjection(
        Long totalRequests,
        Long totalCacheHits,
        Long totalSavedTokens,
        BigDecimal totalSavedCostUsd
) {
    public CacheMetricsProjection {
        totalRequests = totalRequests == null ? 0L : totalRequests;
        totalCacheHits = totalCacheHits == null ? 0L : totalCacheHits;
        totalSavedTokens = totalSavedTokens == null ? 0L : totalSavedTokens;
        totalSavedCostUsd = totalSavedCostUsd == null ? BigDecimal.ZERO : totalSavedCostUsd;
    }
}
