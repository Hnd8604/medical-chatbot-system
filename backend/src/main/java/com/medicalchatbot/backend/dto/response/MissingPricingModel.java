package com.medicalchatbot.backend.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;

public record MissingPricingModel(
        @JsonProperty("llm_provider")
        String llmProvider,
        @JsonProperty("llm_model")
        String llmModel,
        @JsonProperty("request_count")
        int requestCount
) {
}
