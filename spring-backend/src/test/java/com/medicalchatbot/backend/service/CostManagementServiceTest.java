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
import java.util.Optional;
import java.util.UUID;

import com.medicalchatbot.backend.dto.response.CostByDay;
import com.medicalchatbot.backend.dto.response.CostByModel;
import com.medicalchatbot.backend.dto.response.CostSummaryResponse;
import com.medicalchatbot.backend.dto.response.MissingPricingModel;
import com.medicalchatbot.backend.repository.ModelPricingRepository;
import com.medicalchatbot.backend.repository.UserRepository;
import com.medicalchatbot.backend.repository.UsageLogRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class CostManagementServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private UsageLogRepository usageLogRepository;

    @Mock
    private ModelPricingRepository modelPricingRepository;

    // Thêm Mock mới
    @Mock
    private CurrentUserService currentUserService;

    @Test
    void currentUserCostSummaryReturnsTotalsModelsDaysAndMissingPricing() {
        UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000201");
        String username = "test_user";
        CostManagementService service = newService();

        CostByModel byModel = new CostByModel(
                "openai",
                "gpt-4.1-mini",
                2,
                3000,
                700,
                3700,
                new BigDecimal("0.002320")
        );
        CostByDay byDay = new CostByDay(
                LocalDate.parse("2026-06-01"),
                2,
                3000,
                700,
                3700,
                new BigDecimal("0.002320")
        );
        MissingPricingModel missingPricing = new MissingPricingModel("openai", "custom-model", 1);

        // Mock luồng lấy user hiện tại
        when(currentUserService.getCurrentUsername()).thenReturn(username);
        when(userRepository.findIdByUsername(username)).thenReturn(Optional.of(userId));

        when(usageLogRepository.summarizeCost(
                eq(userId),
                any(OffsetDateTime.class),
                any(OffsetDateTime.class)
        )).thenReturn(new CostSummaryResponse(
                null,
                null,
                2,
                3000,
                700,
                3700,
                new BigDecimal("0.002320"),
                List.of(),
                List.of(),
                List.of()
        ));
        when(usageLogRepository.summarizeCostByModel(
                eq(userId),
                any(OffsetDateTime.class),
                any(OffsetDateTime.class)
        )).thenReturn(List.of(byModel));
        when(usageLogRepository.summarizeCostByDay(
                eq(userId),
                any(OffsetDateTime.class),
                any(OffsetDateTime.class),
                eq("Asia/Saigon")
        )).thenReturn(List.of(byDay));
        when(usageLogRepository.findMissingPricingModels(
                eq(userId),
                any(OffsetDateTime.class),
                any(OffsetDateTime.class)
        )).thenReturn(List.of(missingPricing));

        // Gọi hàm mới
        CostSummaryResponse result = service.getCurrentUserCostSummary(
                LocalDate.parse("2026-06-01"),
                LocalDate.parse("2026-06-01")
        );

        assertEquals(LocalDate.parse("2026-06-01"), result.from());
        assertEquals(LocalDate.parse("2026-06-01"), result.to());
        assertEquals(2, result.requestCount());
        assertEquals(3700, result.totalTokens());
        assertEquals(new BigDecimal("0.002320"), result.estimatedCostUsd());
        assertEquals(List.of(byModel), result.models());
        assertEquals(List.of(byDay), result.days());
        assertEquals(List.of(missingPricing), result.missingPricingModels());
    }

    @Test
    void currentUserCostSummaryRejectsInvalidRange() {
        UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000201");
        String username = "test_user";
        CostManagementService service = newService();

        // Cần mock user vì hàm kiểm tra user trước khi check date range
        when(currentUserService.getCurrentUsername()).thenReturn(username);
        when(userRepository.findIdByUsername(username)).thenReturn(Optional.of(userId));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.getCurrentUserCostSummary(
                        LocalDate.parse("2026-06-02"),
                        LocalDate.parse("2026-06-01")
                )
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
    }

    private CostManagementService newService() {
        return new CostManagementService(
                userRepository,
                usageLogRepository,
                modelPricingRepository,
                currentUserService, // Truyền mock mới vào
                ZoneId.of("Asia/Saigon")
        );
    }
}