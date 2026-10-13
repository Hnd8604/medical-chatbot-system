package com.medicalchatbot.backend.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Model có LLM call mà gateway không báo cost (header x-litellm-response-cost thiếu):
 * các call này được ghi cost 0, nên tổng chi phí trong kỳ bị thiếu.
 */
public record CostUnavailableModel(
        @JsonProperty("llm_provider")
        String llmProvider,
        @JsonProperty("llm_model")
        String llmModel,
        @JsonProperty("call_count")
        int callCount
) {
}
