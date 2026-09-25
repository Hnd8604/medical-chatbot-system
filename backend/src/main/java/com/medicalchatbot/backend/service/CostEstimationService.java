package com.medicalchatbot.backend.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

import com.medicalchatbot.backend.repository.ModelPricingRepository;
import com.medicalchatbot.backend.repository.projection.ModelPricingProjection;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CostEstimationService {

    private static final BigDecimal TOKENS_PER_MILLION = new BigDecimal("1000000");
    private static final int COST_SCALE = 6;

    private final ModelPricingRepository modelPricingRepository;

    public BigDecimal estimateUsd(
            String provider,
            String model,
            int inputTokens,
            int outputTokens,
            BigDecimal fallbackEstimatedCostUsd
    ) {
        return modelPricingRepository.findActiveByProviderAndModel(provider, model)
                .map(pricing -> calculate(pricing, inputTokens, outputTokens))
                .orElseGet(() -> normalizeCost(fallbackEstimatedCostUsd));
    }

    private BigDecimal calculate(ModelPricingProjection pricing, int inputTokens, int outputTokens) {
        BigDecimal inputCost = BigDecimal.valueOf(Math.max(0, inputTokens))
                .multiply(pricing.inputPricePer1mTokens())
                .divide(TOKENS_PER_MILLION, COST_SCALE, RoundingMode.HALF_UP);
        BigDecimal outputCost = BigDecimal.valueOf(Math.max(0, outputTokens))
                .multiply(pricing.outputPricePer1mTokens())
                .divide(TOKENS_PER_MILLION, COST_SCALE, RoundingMode.HALF_UP);
        return inputCost.add(outputCost).setScale(COST_SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal normalizeCost(BigDecimal value) {
        if (value == null || value.signum() < 0) {
            return BigDecimal.ZERO.setScale(COST_SCALE, RoundingMode.HALF_UP);
        }
        return value.setScale(COST_SCALE, RoundingMode.HALF_UP);
    }
}
