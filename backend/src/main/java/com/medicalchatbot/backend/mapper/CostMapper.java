package com.medicalchatbot.backend.mapper;

import java.time.LocalDate;
import java.util.List;

import com.medicalchatbot.backend.dto.response.CostSummaryResponse;
import com.medicalchatbot.backend.repository.projection.CostByDayProjection;
import com.medicalchatbot.backend.repository.projection.CostByModelProjection;
import com.medicalchatbot.backend.repository.projection.CostUnavailableModelProjection;
import com.medicalchatbot.backend.repository.projection.UsageTotalsProjection;
import org.springframework.stereotype.Component;

@Component
public class CostMapper {

    public CostSummaryResponse toCostSummary(
            LocalDate from,
            LocalDate to,
            UsageTotalsProjection totals,
            List<CostByModelProjection> models,
            List<CostByDayProjection> days,
            List<CostUnavailableModelProjection> unpricedModels
    ) {
        return new CostSummaryResponse(
                from,
                to,
                totals.requestCount(),
                totals.inputTokens(),
                totals.outputTokens(),
                totals.totalTokens(),
                totals.costUsd(),
                models.stream().map(CostMapper::toModelCost).toList(),
                days.stream().map(CostMapper::toDailyCost).toList(),
                unpricedModels.stream().map(CostMapper::toUnpricedModel).toList()
        );
    }

    private static CostSummaryResponse.ModelCost toModelCost(CostByModelProjection model) {
        return new CostSummaryResponse.ModelCost(
                model.llmProvider(),
                model.llmModel(),
                model.callCount(),
                model.inputTokens(),
                model.outputTokens(),
                model.totalTokens(),
                model.costUsd()
        );
    }

    private static CostSummaryResponse.DailyCost toDailyCost(CostByDayProjection day) {
        return new CostSummaryResponse.DailyCost(
                day.date(),
                day.requestCount(),
                day.inputTokens(),
                day.outputTokens(),
                day.totalTokens(),
                day.costUsd()
        );
    }

    private static CostSummaryResponse.UnpricedModel toUnpricedModel(CostUnavailableModelProjection model) {
        return new CostSummaryResponse.UnpricedModel(model.llmProvider(), model.llmModel(), model.callCount());
    }
}
