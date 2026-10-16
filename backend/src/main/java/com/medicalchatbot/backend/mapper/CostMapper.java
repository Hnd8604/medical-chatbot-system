package com.medicalchatbot.backend.mapper;

import java.time.LocalDate;
import java.util.List;

import com.medicalchatbot.backend.dto.response.usage.CostSummaryResponse;
import com.medicalchatbot.backend.repository.projection.CostByDayProjection;
import com.medicalchatbot.backend.repository.projection.CostByModelProjection;
import com.medicalchatbot.backend.repository.projection.CostUnavailableModelProjection;
import com.medicalchatbot.backend.repository.projection.UsageTotalsProjection;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper
public interface CostMapper {

    @Mapping(target = "totalTokens", expression = "java(totals.totalTokens())")
    @Mapping(target = "costUnavailableModels", source = "unpricedModels")
    CostSummaryResponse toCostSummary(
            LocalDate from,
            LocalDate to,
            UsageTotalsProjection totals,
            List<CostByModelProjection> models,
            List<CostByDayProjection> days,
            List<CostUnavailableModelProjection> unpricedModels
    );

    @Mapping(target = "totalTokens", expression = "java(model.totalTokens())")
    CostSummaryResponse.ModelCost toModelCost(CostByModelProjection model);

    @Mapping(target = "totalTokens", expression = "java(day.totalTokens())")
    CostSummaryResponse.DailyCost toDailyCost(CostByDayProjection day);

    CostSummaryResponse.UnpricedModel toUnpricedModel(CostUnavailableModelProjection model);
}
