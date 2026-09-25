package com.medicalchatbot.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Optional;

import com.medicalchatbot.backend.repository.ModelPricingRepository;
import com.medicalchatbot.backend.repository.projection.ModelPricingProjection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CostEstimationServiceTest {

    @Mock
    private ModelPricingRepository modelPricingRepository;

    @Test
    void estimateUsdCalculatesFromPricingAndRoundsToSixDecimals() {
        CostEstimationService service = new CostEstimationService(modelPricingRepository);
        when(modelPricingRepository.findActiveByProviderAndModel("openai", "gpt-4.1-mini"))
                .thenReturn(Optional.of(new ModelPricingProjection(
                        "openai",
                        "gpt-4.1-mini",
                        new BigDecimal("0.400000"),
                        new BigDecimal("1.600000"),
                        "USD"
                )));

        BigDecimal result = service.estimateUsd(
                "openai",
                "gpt-4.1-mini",
                1234,
                567,
                BigDecimal.ZERO
        );

        assertEquals(new BigDecimal("0.001401"), result);
    }

    @Test
    void estimateUsdFallsBackToChatbotEstimatedCostWhenPricingIsMissing() {
        CostEstimationService service = new CostEstimationService(modelPricingRepository);
        when(modelPricingRepository.findActiveByProviderAndModel("openai", "unknown-model"))
                .thenReturn(Optional.empty());

        BigDecimal result = service.estimateUsd(
                "openai",
                "unknown-model",
                1000,
                500,
                new BigDecimal("0.1234567")
        );

        assertEquals(new BigDecimal("0.123457"), result);
    }

    @Test
    void estimateUsdReturnsZeroWhenPricingAndFallbackAreMissing() {
        CostEstimationService service = new CostEstimationService(modelPricingRepository);
        when(modelPricingRepository.findActiveByProviderAndModel(null, null))
                .thenReturn(Optional.empty());

        BigDecimal result = service.estimateUsd(null, null, 1000, 500, null);

        assertEquals(new BigDecimal("0.000000"), result);
    }
}
