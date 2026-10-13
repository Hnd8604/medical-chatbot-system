package com.medicalchatbot.backend.dto.response;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonProperty;

public record CostByModel(
        @JsonProperty("llm_provider")
        String llmProvider,
        @JsonProperty("llm_model")
        String llmModel,
        /** Số LLM call của model (một lượt chat có thể gọi nhiều call, nhiều model). */
        @JsonProperty("call_count")
        int callCount,
        @JsonProperty("input_tokens")
        int inputTokens,
        @JsonProperty("output_tokens")
        int outputTokens,
        @JsonProperty("total_tokens")
        int totalTokens,
        @JsonProperty("cost_usd")
        BigDecimal costUsd
) {
}
