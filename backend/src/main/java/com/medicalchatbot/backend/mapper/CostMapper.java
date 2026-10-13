package com.medicalchatbot.backend.mapper;

import java.time.LocalDate;
import java.util.List;

import com.medicalchatbot.backend.dto.response.CostByDay;
import com.medicalchatbot.backend.dto.response.CostByModel;
import com.medicalchatbot.backend.dto.response.CostSummaryResponse;
import com.medicalchatbot.backend.dto.response.CostUnavailableModel;
import com.medicalchatbot.backend.repository.projection.CostByDayProjection;
import com.medicalchatbot.backend.repository.projection.CostByModelProjection;
import com.medicalchatbot.backend.repository.projection.CostSummaryProjection;
import com.medicalchatbot.backend.repository.projection.CostUnavailableModelProjection;
import org.springframework.stereotype.Component;

@Component
public class CostMapper {

    public CostSummaryResponse toCostSummary(
            LocalDate from,
            LocalDate to,
            CostSummaryProjection totals,
            List<CostByModelProjection> models,
            List<CostByDayProjection> days,
            List<CostUnavailableModelProjection> costUnavailableModels
    ) {
        return new CostSummaryResponse(
                from,
                to,
                totals.requestCount(),
                totals.inputTokens(),
                totals.outputTokens(),
                totals.totalTokens(),
                totals.costUsd(),
                models.stream().map(this::toCostByModel).toList(),
                days.stream().map(this::toCostByDay).toList(),
                costUnavailableModels.stream().map(this::toCostUnavailableModel).toList()
        );
    }

    private CostByModel toCostByModel(CostByModelProjection model) {
        return new CostByModel(
                model.llmProvider(),
                model.llmModel(),
                model.callCount(),
                model.inputTokens(),
                model.outputTokens(),
                model.totalTokens(),
                model.costUsd()
        );
    }

    private CostByDay toCostByDay(CostByDayProjection day) {
        return new CostByDay(
                day.date(),
                day.requestCount(),
                day.inputTokens(),
                day.outputTokens(),
                day.totalTokens(),
                day.costUsd()
        );
    }

    private CostUnavailableModel toCostUnavailableModel(CostUnavailableModelProjection model) {
        return new CostUnavailableModel(model.llmProvider(), model.llmModel(), model.callCount());
    }
}
