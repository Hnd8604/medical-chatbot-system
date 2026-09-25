package com.medicalchatbot.backend.repository;

import java.time.LocalDate;
import java.util.List;

/**
 * Read-only query contract for the administration analytics use cases.
 *
 * <p>The records in this contract are persistence projections rather than API
 * response models. Keeping that distinction prevents the data-access layer
 * from depending on the web contract.</p>
 */
public interface AnalyticsQueryRepository {

    List<IntentStat> findIntentStats(LocalDate from, LocalDate to, int limit);

    List<ErrorStat> findErrorStats(LocalDate from, LocalDate to, int limit);

    List<PerformanceStat> findPerformanceStats(LocalDate from, LocalDate to, int limit);

    long countChatRequests(LocalDate from, LocalDate to);

    record IntentStat(String date, String intent, long count) {
    }

    record ErrorStat(String date, String service, String errorType, long count) {
    }

    record PerformanceStat(String model, double avgLatency, double p95Latency, double p99Latency) {
    }
}
