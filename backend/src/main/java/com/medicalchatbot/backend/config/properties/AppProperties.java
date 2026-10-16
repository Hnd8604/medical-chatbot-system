package com.medicalchatbot.backend.config.properties;

import java.time.ZoneId;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        /** Calendar days of quotas, cost reports and exports are computed in this zone. */
        @NotNull ZoneId timezone,
        @Valid @NotNull Cors cors,
        @Valid @NotNull RateLimit rateLimit
) {

    public record Cors(@NotEmpty List<@NotBlank String> allowedOrigins) {
    }

    /** Requests per minute allowed on each public auth endpoint, per client IP. */
    public record RateLimit(@Min(1) int authRequestsPerMinute) {
    }
}
