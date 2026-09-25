package com.medicalchatbot.backend.dto.request;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ModelPricingUpsertRequest(
        @NotBlank @Size(max = 50) String provider,

        @NotBlank @Size(max = 100) String model,

        @JsonProperty("input_price_per_1m_tokens")
        @NotNull @DecimalMin("0.0") BigDecimal inputPricePer1mTokens,

        @JsonProperty("output_price_per_1m_tokens")
        @NotNull @DecimalMin("0.0") BigDecimal outputPricePer1mTokens,

        @Size(min = 3, max = 3) String currency,

        Boolean active
) {
    public String currencyOrDefault() {
        return currency == null || currency.isBlank() ? "USD" : currency;
    }

    public boolean activeOrDefault() {
        return active == null || active;
    }
}
