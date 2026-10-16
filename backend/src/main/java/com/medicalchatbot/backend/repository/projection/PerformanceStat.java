package com.medicalchatbot.backend.repository.projection;

/** Chat latency percentiles (milliseconds) of one LLM model. */
public record PerformanceStat(String model, double avgLatency, double p95Latency, double p99Latency) {
}
