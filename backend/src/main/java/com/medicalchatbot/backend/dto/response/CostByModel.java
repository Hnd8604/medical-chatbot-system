package com.medicalchatbot.backend.dto.response;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonProperty;

public record CostByModel(
        @JsonProperty("llm_provider")
        String llmProvider,
        @JsonProperty("llm_model")
        String llmModel,
        @JsonProperty("request_count")
        int requestCount,
        @JsonProperty("input_tokens")
        int inputTokens,
        @JsonProperty("output_tokens")
        int outputTokens,
        @JsonProperty("total_tokens")
        int totalTokens,
        @JsonProperty("estimated_cost_usd")
        BigDecimal estimatedCostUsd
) {
}
