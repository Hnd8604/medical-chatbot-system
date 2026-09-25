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
        @JsonProperty("estimated_cost_usd")
        BigDecimal estimatedCostUsd,
        List<CostByModel> models,
        List<CostByDay> days,
        @JsonProperty("missing_pricing_models")
        List<MissingPricingModel> missingPricingModels
) {
}
