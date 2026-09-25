package com.medicalchatbot.backend.repository.projection;

public record MissingPricingModelProjection(
        String llmProvider,
        String llmModel,
        int requestCount
) {
}
