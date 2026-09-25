package com.medicalchatbot.backend.service;

import com.medicalchatbot.backend.dto.response.CacheMetricsResponse;
import com.medicalchatbot.backend.repository.UsageLogRepository;
import com.medicalchatbot.backend.repository.projection.CacheMetricsProjection;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Service
@RequiredArgsConstructor
public class MetricsService {
    private final UsageLogRepository usageLogRepository;

    public CacheMetricsResponse getCacheMetrics(OffsetDateTime startDate, OffsetDateTime endDate) {
        return toResponse(usageLogRepository.getCacheObservabilityMetrics(startDate, endDate));
    }

    public CacheMetricsResponse getCacheMetrics() {
        OffsetDateTime endDate = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime startDate = endDate.minusDays(30);

        return toResponse(usageLogRepository.getCacheObservabilityMetrics(startDate, endDate));
    }

    private CacheMetricsResponse toResponse(CacheMetricsProjection metrics) {
        return new CacheMetricsResponse(
                metrics.totalRequests(),
                metrics.totalCacheHits(),
                metrics.totalSavedTokens(),
                metrics.totalSavedCostUsd()
        );
    }
}
