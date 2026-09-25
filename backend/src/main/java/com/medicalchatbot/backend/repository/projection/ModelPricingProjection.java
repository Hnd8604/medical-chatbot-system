package com.medicalchatbot.backend.repository.projection;

import java.math.BigDecimal;

public record ModelPricingProjection(
        String provider,
        String model,
        BigDecimal inputPricePer1mTokens,
        BigDecimal outputPricePer1mTokens,
        String currency
) {
}
