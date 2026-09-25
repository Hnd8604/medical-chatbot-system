package com.medicalchatbot.backend.service;

import com.medicalchatbot.backend.dto.response.ErrorAnalyticsResponse;
import com.medicalchatbot.backend.dto.response.IntentAnalyticsResponse;
import com.medicalchatbot.backend.dto.response.PerformanceAnalyticsResponse;
import com.medicalchatbot.backend.dto.response.RequestAnalyticsSummaryResponse;
import com.medicalchatbot.backend.exception.AppException;
import com.medicalchatbot.backend.exception.ErrorCode;
import com.medicalchatbot.backend.repository.AnalyticsQueryRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
public class AnalyticsService {

    private static final int DEFAULT_DAYS = 7;
    private static final int MAX_RANGE_DAYS = 90;
    private static final int MAX_LIMIT = 20;

    private final AnalyticsQueryRepository analyticsQueryRepository;

    public AnalyticsService(AnalyticsQueryRepository analyticsQueryRepository) {
        this.analyticsQueryRepository = analyticsQueryRepository;
    }

    public List<IntentAnalyticsResponse> getIntentAnalytics(LocalDate from, LocalDate to, int limit) {
        DateRange range = normalizeRange(from, to);
        int safeLimit = normalizeLimit(limit);

        return analyticsQueryRepository.findIntentStats(range.from(), range.to(), safeLimit)
                .stream()
                .map(stat -> new IntentAnalyticsResponse(stat.date(), stat.intent(), stat.count()))
                .toList();
    }

    public List<ErrorAnalyticsResponse> getErrorAnalytics(LocalDate from, LocalDate to, int limit) {
        DateRange range = normalizeRange(from, to);
        int safeLimit = normalizeLimit(limit);

        return analyticsQueryRepository.findErrorStats(range.from(), range.to(), safeLimit)
                .stream()
                .map(stat -> new ErrorAnalyticsResponse(
                        stat.date(),
                        stat.service(),
                        stat.errorType(),
                        stat.count()
                ))
                .toList();
    }

    public List<PerformanceAnalyticsResponse> getPerformanceAnalytics(LocalDate from, LocalDate to, int limit) {
        DateRange range = normalizeRange(from, to);
        int safeLimit = normalizeLimit(limit);

        return analyticsQueryRepository.findPerformanceStats(range.from(), range.to(), safeLimit)
                .stream()
                .map(stat -> new PerformanceAnalyticsResponse(
                        stat.model(),
                        stat.avgLatency(),
                        stat.p95Latency(),
                        stat.p99Latency()
                ))
                .toList();
    }

    public RequestAnalyticsSummaryResponse getRequestSummary(LocalDate from, LocalDate to) {
        DateRange range = normalizeRange(from, to);

        return new RequestAnalyticsSummaryResponse(
                range.from().toString(),
                range.to().toString(),
                analyticsQueryRepository.countChatRequests(range.from(), range.to())
        );
    }

    private DateRange normalizeRange(LocalDate from, LocalDate to) {
        LocalDate endDate = to != null ? to : LocalDate.now();
        LocalDate startDate = from != null ? from : endDate.minusDays(DEFAULT_DAYS - 1L);
        if (startDate.isAfter(endDate)) {
            throw new AppException(ErrorCode.INVALID_ARGUMENT, "from must be before or equal to to.");
        }
        long days = ChronoUnit.DAYS.between(startDate, endDate) + 1;
        if (days > MAX_RANGE_DAYS) {
            throw new AppException(ErrorCode.INVALID_ARGUMENT, "Analytics range cannot exceed 90 days.");
        }
        return new DateRange(startDate, endDate);
    }

    private int normalizeLimit(int limit) {
        if (limit < 1) {
            return 1;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private record DateRange(LocalDate from, LocalDate to) {
    }
}
