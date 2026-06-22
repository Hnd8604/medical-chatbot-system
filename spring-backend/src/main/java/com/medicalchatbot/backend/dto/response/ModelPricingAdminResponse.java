package com.medicalchatbot.backend.dto.response;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

import com.medicalchatbot.backend.entity.ModelPricing;

public record ModelPricingAdminResponse(
        UUID id,
        String provider,
        String model,

        @JsonProperty("input_price_per_1m_tokens")
        BigDecimal inputPricePer1mTokens,

        @JsonProperty("output_price_per_1m_tokens")
        BigDecimal outputPricePer1mTokens,

        String currency,
        boolean active,

        @JsonProperty("updated_at")
        OffsetDateTime updatedAt
) {
    public static ModelPricingAdminResponse from(ModelPricing pricing) {
        return new ModelPricingAdminResponse(
                pricing.getId(),
                pricing.getProvider(),
                pricing.getModel(),
                pricing.getInputPricePer1mTokens(),
                pricing.getOutputPricePer1mTokens(),
                pricing.getCurrency(),
                pricing.isActive(),
                pricing.getUpdatedAt()
        );
    }
}
