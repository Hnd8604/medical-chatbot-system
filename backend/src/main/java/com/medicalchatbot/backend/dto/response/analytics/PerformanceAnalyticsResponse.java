package com.medicalchatbot.backend.dto.response.analytics;

/** Chat latency of one model, in milliseconds. */
public record PerformanceAnalyticsResponse(String model, double avgLatency, double p95Latency, double p99Latency) {
}
