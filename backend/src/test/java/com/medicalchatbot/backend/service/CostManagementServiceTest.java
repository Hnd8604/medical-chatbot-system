package com.medicalchatbot.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import com.medicalchatbot.backend.dto.response.CostByDay;
import com.medicalchatbot.backend.dto.response.CostByModel;
import com.medicalchatbot.backend.dto.response.CostSummaryResponse;
import com.medicalchatbot.backend.dto.response.MissingPricingModel;
import com.medicalchatbot.backend.exception.AppException;
import com.medicalchatbot.backend.exception.ErrorCode;
import com.medicalchatbot.backend.repository.ModelPricingRepository;
import com.medicalchatbot.backend.repository.UsageLogRepository;
import com.medicalchatbot.backend.repository.projection.CostByDayProjection;
import com.medicalchatbot.backend.repository.projection.CostByModelProjection;
import com.medicalchatbot.backend.repository.projection.CostSummaryProjection;
import com.medicalchatbot.backend.repository.projection.MissingPricingModelProjection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CostManagementServiceTest {

    @Mock
    private UsageLogRepository usageLogRepository;

    @Mock
    private ModelPricingRepository modelPricingRepository;

    @Mock
    private CurrentUserService currentUserService;

    @Test
    void currentUserCostSummaryReturnsTotalsModelsDaysAndMissingPricing() {
        UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000201");
        CostManagementService service = newService();
        CostByModelProjection byModelProjection = new CostByModelProjection(
                "openai",
                "gpt-4.1-mini",
                2,
                3000,
                700,
                new BigDecimal("0.002320")
        );
        CostByDayProjection byDayProjection = new CostByDayProjection(
                LocalDate.parse("2026-06-01"),
                2,
                3000,
                700,
                new BigDecimal("0.002320")
        );
        MissingPricingModelProjection missingPricingProjection =
                new MissingPricingModelProjection("openai", "custom-model", 1);

        when(currentUserService.requireCurrentUserId()).thenReturn(userId);
        when(usageLogRepository.summarizeCost(
                eq(userId),
                any(OffsetDateTime.class),
                any(OffsetDateTime.class)
        )).thenReturn(new CostSummaryProjection(
                2,
                3000,
                700,
                new BigDecimal("0.002320")
        ));
        when(usageLogRepository.summarizeCostByModel(
                eq(userId),
                any(OffsetDateTime.class),
                any(OffsetDateTime.class)
        )).thenReturn(List.of(byModelProjection));
        when(usageLogRepository.summarizeCostByDay(
                eq(userId),
                any(OffsetDateTime.class),
                any(OffsetDateTime.class),
                eq("Asia/Saigon")
        )).thenReturn(List.of(byDayProjection));
        when(usageLogRepository.findMissingPricingModels(
                eq(userId),
                any(OffsetDateTime.class),
                any(OffsetDateTime.class)
        )).thenReturn(List.of(missingPricingProjection));

        CostSummaryResponse result = service.currentUserCostSummary(
                LocalDate.parse("2026-06-01"),
                LocalDate.parse("2026-06-01")
        );

        assertEquals(LocalDate.parse("2026-06-01"), result.from());
        assertEquals(LocalDate.parse("2026-06-01"), result.to());
        assertEquals(2, result.requestCount());
        assertEquals(3700, result.totalTokens());
        assertEquals(new BigDecimal("0.002320"), result.estimatedCostUsd());
        assertEquals(List.of(new CostByModel(
                "openai", "gpt-4.1-mini", 2, 3000, 700, 3700, new BigDecimal("0.002320")
        )), result.models());
        assertEquals(List.of(new CostByDay(
                LocalDate.parse("2026-06-01"), 2, 3000, 700, 3700, new BigDecimal("0.002320")
        )), result.days());
        assertEquals(List.of(new MissingPricingModel("openai", "custom-model", 1)), result.missingPricingModels());
    }

    @Test
    void currentUserCostSummaryRejectsInvalidRange() {
        CostManagementService service = newService();

        AppException exception = assertThrows(
                AppException.class,
                () -> service.currentUserCostSummary(
                        LocalDate.parse("2026-06-02"),
                        LocalDate.parse("2026-06-01")
                )
        );

        assertEquals(ErrorCode.INVALID_ARGUMENT, exception.getErrorCode());
    }

    private CostManagementService newService() {
        return new CostManagementService(
                usageLogRepository,
                modelPricingRepository,
                ZoneId.of("Asia/Saigon"),
                currentUserService
        );
    }
}
