package com.medicalchatbot.backend.service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import com.medicalchatbot.backend.config.properties.AppProperties;
import com.medicalchatbot.backend.dto.response.CacheMetricsResponse;
import com.medicalchatbot.backend.dto.response.ErrorAnalyticsResponse;
import com.medicalchatbot.backend.dto.response.IntentAnalyticsResponse;
import com.medicalchatbot.backend.dto.response.PerformanceAnalyticsResponse;
import com.medicalchatbot.backend.dto.response.RequestCountResponse;
import com.medicalchatbot.backend.exception.AppException;
import com.medicalchatbot.backend.repository.AnalyticsRepository;
import com.medicalchatbot.backend.repository.UsageLogRepository;
import com.medicalchatbot.backend.repository.projection.CacheMetricsProjection;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Aggregates for the admin dashboard and analytics screens. */
@Service
@RequiredArgsConstructor
public class AnalyticsService {

    private static final int DEFAULT_DAYS = 7;
    private static final int MAX_RANGE_DAYS = 90;
    private static final int MAX_LIMIT = 20;
    private static final int DEFAULT_CACHE_WINDOW_DAYS = 30;

    private final AnalyticsRepository analyticsRepository;
    private final UsageLogRepository usageLogRepository;
    private final AppProperties appProperties;

    public List<IntentAnalyticsResponse> intents(LocalDate from, LocalDate to, int limit) {
        DateRange range = DateRange.of(from, to, today());
        return analyticsRepository.findIntentStats(range.from(), range.to(), clampLimit(limit)).stream()
                .map(stat -> new IntentAnalyticsResponse(stat.date(), stat.intent(), stat.count()))
                .toList();
    }

    public List<ErrorAnalyticsResponse> errors(LocalDate from, LocalDate to, int limit) {
        DateRange range = DateRange.of(from, to, today());
        return analyticsRepository.findErrorStats(range.from(), range.to(), clampLimit(limit)).stream()
                .map(stat -> new ErrorAnalyticsResponse(stat.date(), stat.service(), stat.errorType(), stat.count()))
                .toList();
    }

    public List<PerformanceAnalyticsResponse> performance(LocalDate from, LocalDate to, int limit) {
        DateRange range = DateRange.of(from, to, today());
        return analyticsRepository.findPerformanceStats(range.from(), range.to(), clampLimit(limit)).stream()
                .map(stat -> new PerformanceAnalyticsResponse(
                        stat.model(), stat.avgLatency(), stat.p95Latency(), stat.p99Latency()))
                .toList();
    }

    public RequestCountResponse requestCount(LocalDate from, LocalDate to) {
        DateRange range = DateRange.of(from, to, today());
        return new RequestCountResponse(range.from(), range.to(),
                analyticsRepository.countChatRequests(range.from(), range.to()));
    }

    /** Semantic-cache metrics over {@code [startDate, endDate]}; the last 30 days by default. */
    public CacheMetricsResponse cacheMetrics(OffsetDateTime startDate, OffsetDateTime endDate) {
        OffsetDateTime end = endDate != null ? endDate : OffsetDateTime.now(appProperties.timezone());
        OffsetDateTime start = startDate != null ? startDate : end.minusDays(DEFAULT_CACHE_WINDOW_DAYS);
        if (start.isAfter(end)) {
            throw AppException.invalid("Ngày bắt đầu phải trước ngày kết thúc.");
        }
        CacheMetricsProjection metrics = usageLogRepository.getCacheMetrics(start, end);
        return CacheMetricsResponse.of(
                metrics.totalRequests(),
                metrics.totalCacheHits(),
                metrics.totalSavedTokens(),
                metrics.totalSavedCostUsd()
        );
    }

    private LocalDate today() {
        return LocalDate.now(appProperties.timezone());
    }

    private static int clampLimit(int limit) {
        return Math.max(1, Math.min(limit, MAX_LIMIT));
    }

    /** Inclusive date range; defaults to the last 7 days and may span at most 90 days. */
    private record DateRange(LocalDate from, LocalDate to) {

        static DateRange of(LocalDate from, LocalDate to, LocalDate today) {
            LocalDate end = to != null ? to : today;
            LocalDate start = from != null ? from : end.minusDays(DEFAULT_DAYS - 1L);
            if (start.isAfter(end)) {
                throw AppException.invalid("Ngày bắt đầu phải trước hoặc bằng ngày kết thúc.");
            }
            if (ChronoUnit.DAYS.between(start, end) + 1 > MAX_RANGE_DAYS) {
                throw AppException.invalid("Khoảng thời gian phân tích không được vượt quá " + MAX_RANGE_DAYS + " ngày.");
            }
            return new DateRange(start, end);
        }
    }
}
