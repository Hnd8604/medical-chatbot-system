package com.medicalchatbot.backend.repository.projection;

public record CostUnavailableModelProjection(
        String llmProvider,
        String llmModel,
        int callCount
) {
}
