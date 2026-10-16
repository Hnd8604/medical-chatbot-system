package com.medicalchatbot.backend.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** AI usage and cost of one user over {@code [from, to]}, by model and by day. */
public record CostSummaryResponse(
        LocalDate from,
        LocalDate to,
        int requestCount,
        int inputTokens,
        int outputTokens,
        int totalTokens,
        BigDecimal costUsd,
        List<ModelCost> models,
        List<DailyCost> days,
        /** Models with calls the gateway did not price; {@code costUsd} is missing their share. */
        List<UnpricedModel> costUnavailableModels
) {

    /** {@code callCount} counts LLM calls: one chat request can make several calls to several models. */
    public record ModelCost(
            String llmProvider,
            String llmModel,
            int callCount,
            int inputTokens,
            int outputTokens,
            int totalTokens,
            BigDecimal costUsd
    ) {
    }

    public record DailyCost(
            LocalDate date,
            int requestCount,
            int inputTokens,
            int outputTokens,
            int totalTokens,
            BigDecimal costUsd
    ) {
    }

    public record UnpricedModel(String llmProvider, String llmModel, int callCount) {
    }
}
