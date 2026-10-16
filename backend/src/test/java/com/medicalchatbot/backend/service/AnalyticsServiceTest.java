package com.medicalchatbot.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.medicalchatbot.backend.dto.response.CacheMetricsResponse;
import com.medicalchatbot.backend.dto.response.ErrorAnalyticsResponse;
import com.medicalchatbot.backend.dto.response.IntentAnalyticsResponse;
import com.medicalchatbot.backend.dto.response.PerformanceAnalyticsResponse;
import com.medicalchatbot.backend.dto.response.RequestCountResponse;
import com.medicalchatbot.backend.exception.AppException;
import com.medicalchatbot.backend.exception.ErrorCode;
import com.medicalchatbot.backend.repository.AnalyticsRepository;
import com.medicalchatbot.backend.repository.UsageLogRepository;
import com.medicalchatbot.backend.repository.projection.CacheMetricsProjection;
import com.medicalchatbot.backend.repository.projection.ErrorStat;
import com.medicalchatbot.backend.repository.projection.IntentStat;
import com.medicalchatbot.backend.repository.projection.PerformanceStat;
import com.medicalchatbot.backend.support.TestProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AnalyticsServiceTest {

    private static final LocalDate FROM = LocalDate.of(2026, 9, 1);
    private static final LocalDate TO = LocalDate.of(2026, 9, 7);

    @Mock
    private AnalyticsRepository analyticsRepository;

    @Mock
    private UsageLogRepository usageLogRepository;

    private AnalyticsService service;

    @BeforeEach
    void setUp() {
        service = new AnalyticsService(analyticsRepository, usageLogRepository, TestProperties.app());
    }

    @Test
    void intentsMapProjectionAndClampUpperLimit() {
        when(analyticsRepository.findIntentStats(FROM, TO, 20))
                .thenReturn(List.of(new IntentStat("2026-09-03", "symptom_check", 12)));

        List<IntentAnalyticsResponse> result = service.intents(FROM, TO, 100);

        assertEquals(List.of(new IntentAnalyticsResponse("2026-09-03", "symptom_check", 12)), result);
    }

    @Test
    void errorsMapProjectionAndClampLowerLimit() {
        when(analyticsRepository.findErrorStats(FROM, TO, 1))
                .thenReturn(List.of(new ErrorStat("2026-09-04", "chatbot-service", "UPSTREAM_ERROR", 3)));

        List<ErrorAnalyticsResponse> result = service.errors(FROM, TO, 0);

        assertEquals(List.of(new ErrorAnalyticsResponse("2026-09-04", "chatbot-service", "UPSTREAM_ERROR", 3)), result);
    }

    @Test
    void performanceMapsProjection() {
        when(analyticsRepository.findPerformanceStats(FROM, TO, 10))
                .thenReturn(List.of(new PerformanceStat("gpt-4.1-mini", 350.5, 610.0, 820.0)));

        List<PerformanceAnalyticsResponse> result = service.performance(FROM, TO, 10);

        assertEquals(List.of(new PerformanceAnalyticsResponse("gpt-4.1-mini", 350.5, 610.0, 820.0)), result);
    }

    @Test
    void requestCountKeepsTheRequestedRange() {
        when(analyticsRepository.countChatRequests(FROM, TO)).thenReturn(42L);

        assertEquals(new RequestCountResponse(FROM, TO, 42L), service.requestCount(FROM, TO));
    }

    @Test
    void missingRangeDefaultsToTheLastSevenDays() {
        LocalDate today = LocalDate.now(TestProperties.VIETNAM);
        when(analyticsRepository.countChatRequests(today.minusDays(6), today)).thenReturn(1L);

        assertEquals(today.minusDays(6), service.requestCount(null, null).from());
    }

    @Test
    void rejectsInvertedRangeBeforeQuerying() {
        AppException exception = assertThrows(AppException.class, () -> service.intents(TO, FROM, 10));

        assertEquals(ErrorCode.INVALID_ARGUMENT, exception.getErrorCode());
        verifyNoInteractions(analyticsRepository);
    }

    @Test
    void rejectsRangeLongerThanNinetyDaysBeforeQuerying() {
        AppException exception = assertThrows(AppException.class,
                () -> service.requestCount(FROM, FROM.plusDays(90)));

        assertEquals(ErrorCode.INVALID_ARGUMENT, exception.getErrorCode());
        verifyNoInteractions(analyticsRepository);
    }

    @Test
    void cacheMetricsComputeHitRate() {
        when(usageLogRepository.getCacheMetrics(any(), any()))
                .thenReturn(new CacheMetricsProjection(40L, 10L, 5000L, new BigDecimal("0.0123")));

        CacheMetricsResponse metrics = service.cacheMetrics(null, null);

        assertEquals(25.0, metrics.hitRatePercentage());
        assertEquals(5000L, metrics.totalSavedTokens());
        verify(usageLogRepository).getCacheMetrics(any(), any());
    }

    @Test
    void cacheMetricsWithoutRequestsHaveZeroHitRate() {
        when(usageLogRepository.getCacheMetrics(any(), any()))
                .thenReturn(new CacheMetricsProjection(null, null, null, null));

        assertEquals(0.0, service.cacheMetrics(null, null).hitRatePercentage());
    }
}
