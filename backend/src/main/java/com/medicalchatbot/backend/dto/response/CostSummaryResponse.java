package com.medicalchatbot.backend.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

public record CostSummaryResponse(
        LocalDate from,
        LocalDate to,
        @JsonProperty("request_count")
        int requestCount,
        @JsonProperty("input_tokens")
        int inputTokens,
        @JsonProperty("output_tokens")
        int outputTokens,
        @JsonProperty("total_tokens")
        int totalTokens,
        @JsonProperty("cost_usd")
        BigDecimal costUsd,
        List<CostByModel> models,
        List<CostByDay> days,
        /** Model có call mà gateway không báo cost — tổng {@code cost_usd} bị thiếu phần này. */
        @JsonProperty("cost_unavailable_models")
        List<CostUnavailableModel> costUnavailableModels
) {
}
