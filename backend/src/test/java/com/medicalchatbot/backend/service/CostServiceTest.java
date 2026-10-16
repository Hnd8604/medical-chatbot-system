package com.medicalchatbot.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import com.medicalchatbot.backend.dto.response.CostSummaryResponse;
import com.medicalchatbot.backend.exception.AppException;
import com.medicalchatbot.backend.exception.ErrorCode;
import com.medicalchatbot.backend.mapper.CostMapper;
import com.medicalchatbot.backend.repository.UsageLogRepository;
import com.medicalchatbot.backend.repository.UserRepository;
import com.medicalchatbot.backend.repository.projection.CostByDayProjection;
import com.medicalchatbot.backend.repository.projection.CostByModelProjection;
import com.medicalchatbot.backend.repository.projection.CostUnavailableModelProjection;
import com.medicalchatbot.backend.repository.projection.UsageTotalsProjection;
import com.medicalchatbot.backend.support.TestProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CostServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Saigon");
    private static final LocalDate DAY = LocalDate.parse("2026-06-01");

    @Mock
    private UsageLogRepository usageLogRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private CurrentUserService currentUserService;

    private CostService service;

    @BeforeEach
    void setUp() {
        service = new CostService(usageLogRepository, userRepository, currentUserService, new CostMapper(),
                TestProperties.app(ZONE));
    }

    @Test
    void currentUserSummaryReturnsTotalsModelsDaysAndUnpricedModels() {
        UUID userId = UUID.randomUUID();
        when(currentUserService.requireCurrentUserId()).thenReturn(userId);
        when(usageLogRepository.summarizeUsage(eq(userId), any(), any()))
                .thenReturn(new UsageTotalsProjection(2, 3000, 700, new BigDecimal("0.002320")));
        when(usageLogRepository.summarizeCostByModel(eq(userId), any(), any())).thenReturn(List.of(
                new CostByModelProjection("openai", "gpt-4.1-mini", 2, 3000, 700, new BigDecimal("0.002320"))));
        when(usageLogRepository.summarizeCostByDay(eq(userId), any(), any(), eq("Asia/Saigon"))).thenReturn(List.of(
                new CostByDayProjection(DAY, 2, 3000, 700, new BigDecimal("0.002320"))));
        when(usageLogRepository.findCostUnavailableModels(eq(userId), any(), any()))
                .thenReturn(List.of(new CostUnavailableModelProjection("openai", "custom-model", 1)));

        CostSummaryResponse result = service.currentUserSummary(DAY, DAY);

        assertEquals(DAY, result.from());
        assertEquals(2, result.requestCount());
        assertEquals(3700, result.totalTokens());
        assertEquals(new BigDecimal("0.002320"), result.costUsd());
        assertEquals(List.of(new CostSummaryResponse.ModelCost(
                "openai", "gpt-4.1-mini", 2, 3000, 700, 3700, new BigDecimal("0.002320"))), result.models());
        assertEquals(List.of(new CostSummaryResponse.DailyCost(
                DAY, 2, 3000, 700, 3700, new BigDecimal("0.002320"))), result.days());
        assertEquals(List.of(new CostSummaryResponse.UnpricedModel("openai", "custom-model", 1)),
                result.costUnavailableModels());
    }

    @Test
    void summaryCoversWholeCalendarDaysOfTheApplicationZone() {
        UUID userId = UUID.randomUUID();
        when(currentUserService.requireCurrentUserId()).thenReturn(userId);
        when(usageLogRepository.summarizeUsage(any(), any(), any()))
                .thenReturn(new UsageTotalsProjection(0, 0, 0, BigDecimal.ZERO));

        service.currentUserSummary(DAY, DAY);

        verify(usageLogRepository).summarizeUsage(userId,
                OffsetDateTime.parse("2026-06-01T00:00:00+07:00"),
                OffsetDateTime.parse("2026-06-02T00:00:00+07:00"));
    }

    @Test
    void rejectsInvertedRange() {
        AppException exception = assertThrows(AppException.class,
                () -> service.currentUserSummary(DAY.plusDays(1), DAY));

        assertEquals(ErrorCode.INVALID_ARGUMENT, exception.getErrorCode());
    }

    @Test
    void adminSummaryOfUnknownUserIsNotFound() {
        UUID userId = UUID.randomUUID();
        when(userRepository.existsById(userId)).thenReturn(false);

        AppException exception = assertThrows(AppException.class, () -> service.userSummary(userId, DAY, DAY));

        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, exception.getErrorCode());
    }
}
