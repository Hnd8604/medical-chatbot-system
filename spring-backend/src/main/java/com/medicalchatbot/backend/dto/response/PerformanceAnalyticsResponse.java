package com.medicalchatbot.backend.dto.response;

public record PerformanceAnalyticsResponse(String model, double avgLatency, double p95Latency, double p99Latency) {
}
