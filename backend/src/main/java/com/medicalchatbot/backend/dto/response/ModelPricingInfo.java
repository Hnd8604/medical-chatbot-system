package com.medicalchatbot.backend.dto.response;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ModelPricingInfo(
        String provider,
        String model,
        @JsonProperty("input_price_per_1m_tokens")
        BigDecimal inputPricePer1mTokens,
        @JsonProperty("output_price_per_1m_tokens")
        BigDecimal outputPricePer1mTokens,
        String currency
) {
}
