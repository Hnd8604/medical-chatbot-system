package com.medicalchatbot.backend.mapper;

import java.time.LocalDate;
import java.util.List;

import com.medicalchatbot.backend.dto.response.CostByDay;
import com.medicalchatbot.backend.dto.response.CostByModel;
import com.medicalchatbot.backend.dto.response.CostSummaryResponse;
import com.medicalchatbot.backend.dto.response.MissingPricingModel;
import com.medicalchatbot.backend.dto.response.ModelPricingInfo;
import com.medicalchatbot.backend.repository.projection.CostByDayProjection;
import com.medicalchatbot.backend.repository.projection.CostByModelProjection;
import com.medicalchatbot.backend.repository.projection.CostSummaryProjection;
import com.medicalchatbot.backend.repository.projection.MissingPricingModelProjection;
import com.medicalchatbot.backend.repository.projection.ModelPricingProjection;
import org.springframework.stereotype.Component;

@Component
public class CostMapper {

    public List<ModelPricingInfo> toPricingInfo(List<ModelPricingProjection> pricing) {
        return pricing.stream().map(this::toPricingInfo).toList();
    }

    public ModelPricingInfo toPricingInfo(ModelPricingProjection pricing) {
        return new ModelPricingInfo(
                pricing.provider(),
                pricing.model(),
                pricing.inputPricePer1mTokens(),
                pricing.outputPricePer1mTokens(),
                pricing.currency()
        );
    }

    public CostSummaryResponse toCostSummary(
            LocalDate from,
            LocalDate to,
            CostSummaryProjection totals,
            List<CostByModelProjection> models,
            List<CostByDayProjection> days,
            List<MissingPricingModelProjection> missingPricingModels
    ) {
        return new CostSummaryResponse(
                from,
                to,
                totals.requestCount(),
                totals.inputTokens(),
                totals.outputTokens(),
                totals.totalTokens(),
                totals.estimatedCostUsd(),
                models.stream().map(this::toCostByModel).toList(),
                days.stream().map(this::toCostByDay).toList(),
                missingPricingModels.stream().map(this::toMissingPricingModel).toList()
        );
    }

    private CostByModel toCostByModel(CostByModelProjection model) {
        return new CostByModel(
                model.llmProvider(),
                model.llmModel(),
                model.requestCount(),
                model.inputTokens(),
                model.outputTokens(),
                model.totalTokens(),
                model.estimatedCostUsd()
        );
    }

    private CostByDay toCostByDay(CostByDayProjection day) {
        return new CostByDay(
                day.date(),
                day.requestCount(),
                day.inputTokens(),
                day.outputTokens(),
                day.totalTokens(),
                day.estimatedCostUsd()
        );
    }

    private MissingPricingModel toMissingPricingModel(MissingPricingModelProjection model) {
        return new MissingPricingModel(model.llmProvider(), model.llmModel(), model.requestCount());
    }
}
