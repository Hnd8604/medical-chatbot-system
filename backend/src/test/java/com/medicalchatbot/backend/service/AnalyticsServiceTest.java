package com.medicalchatbot.backend.service;

import com.medicalchatbot.backend.dto.response.ErrorAnalyticsResponse;
import com.medicalchatbot.backend.dto.response.IntentAnalyticsResponse;
import com.medicalchatbot.backend.dto.response.PerformanceAnalyticsResponse;
import com.medicalchatbot.backend.dto.response.RequestAnalyticsSummaryResponse;
import com.medicalchatbot.backend.exception.AppException;
import com.medicalchatbot.backend.exception.ErrorCode;
import com.medicalchatbot.backend.repository.AnalyticsQueryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalyticsServiceTest {

    private static final LocalDate FROM = LocalDate.of(2026, 9, 1);
    private static final LocalDate TO = LocalDate.of(2026, 9, 7);

    @Mock
    private AnalyticsQueryRepository analyticsQueryRepository;

    private AnalyticsService analyticsService;

    @BeforeEach
    void setUp() {
        analyticsService = new AnalyticsService(analyticsQueryRepository);
    }

    @Test
    void getIntentAnalyticsMapsRepositoryProjectionAndClampsUpperLimit() {
        when(analyticsQueryRepository.findIntentStats(FROM, TO, 20))
                .thenReturn(List.of(new AnalyticsQueryRepository.IntentStat(
                        "2026-09-03",
                        "symptom_check",
                        12
                )));

        List<IntentAnalyticsResponse> result = analyticsService.getIntentAnalytics(FROM, TO, 100);

        assertEquals(List.of(new IntentAnalyticsResponse("2026-09-03", "symptom_check", 12)), result);
        verify(analyticsQueryRepository).findIntentStats(FROM, TO, 20);
    }

    @Test
    void getErrorAnalyticsMapsRepositoryProjectionAndClampsLowerLimit() {
        when(analyticsQueryRepository.findErrorStats(FROM, TO, 1))
                .thenReturn(List.of(new AnalyticsQueryRepository.ErrorStat(
                        "2026-09-04",
                        "chatbot-service",
                        "UPSTREAM_ERROR",
                        3
                )));

        List<ErrorAnalyticsResponse> result = analyticsService.getErrorAnalytics(FROM, TO, 0);

        assertEquals(List.of(new ErrorAnalyticsResponse(
                "2026-09-04",
                "chatbot-service",
                "UPSTREAM_ERROR",
                3
        )), result);
        verify(analyticsQueryRepository).findErrorStats(FROM, TO, 1);
    }

    @Test
    void getPerformanceAnalyticsMapsRepositoryProjection() {
        when(analyticsQueryRepository.findPerformanceStats(FROM, TO, 10))
                .thenReturn(List.of(new AnalyticsQueryRepository.PerformanceStat(
                        "gpt-4.1-mini",
                        350.5,
                        610.0,
                        820.0
                )));

        List<PerformanceAnalyticsResponse> result = analyticsService.getPerformanceAnalytics(FROM, TO, 10);

        assertEquals(List.of(new PerformanceAnalyticsResponse(
                "gpt-4.1-mini",
                350.5,
                610.0,
                820.0
        )), result);
        verify(analyticsQueryRepository).findPerformanceStats(FROM, TO, 10);
    }

    @Test
    void getRequestSummaryPreservesRequestedRangeAndCount() {
        when(analyticsQueryRepository.countChatRequests(FROM, TO)).thenReturn(42L);

        RequestAnalyticsSummaryResponse result = analyticsService.getRequestSummary(FROM, TO);

        assertEquals(new RequestAnalyticsSummaryResponse("2026-09-01", "2026-09-07", 42L), result);
        verify(analyticsQueryRepository).countChatRequests(FROM, TO);
    }

    @Test
    void rejectsInvertedRangeBeforeQueryingRepository() {
        AppException exception = assertThrows(
                AppException.class,
                () -> analyticsService.getIntentAnalytics(TO, FROM, 10)
        );

        assertEquals(ErrorCode.INVALID_ARGUMENT, exception.getErrorCode());
        assertEquals("from must be before or equal to to.", exception.getMessage());
        verifyNoInteractions(analyticsQueryRepository);
    }

    @Test
    void rejectsRangeLongerThanNinetyDaysBeforeQueryingRepository() {
        AppException exception = assertThrows(
                AppException.class,
                () -> analyticsService.getRequestSummary(FROM, FROM.plusDays(90))
        );

        assertEquals(ErrorCode.INVALID_ARGUMENT, exception.getErrorCode());
        assertEquals("Analytics range cannot exceed 90 days.", exception.getMessage());
        verifyNoInteractions(analyticsQueryRepository);
    }
}
