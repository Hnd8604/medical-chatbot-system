package com.medicalchatbot.backend.service;

import com.medicalchatbot.backend.dto.response.CacheMetricsResponse;
import com.medicalchatbot.backend.repository.UsageLogRepository;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Service
public class MetricsService {
    private final UsageLogRepository usageLogRepository;

    public MetricsService(UsageLogRepository usageLogRepository) {
        this.usageLogRepository = usageLogRepository;
    }

    public CacheMetricsResponse getCacheMetrics(OffsetDateTime startDate, OffsetDateTime endDate) {
        return usageLogRepository.getCacheObservabilityMetrics(startDate, endDate);
    }

    public CacheMetricsResponse getCacheMetrics() {
        OffsetDateTime endDate = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime startDate = endDate.minusDays(30);

        return usageLogRepository.getCacheObservabilityMetrics(startDate, endDate);
    }
}
