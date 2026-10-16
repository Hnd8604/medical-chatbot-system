package com.medicalchatbot.backend.config.properties;

import java.time.Duration;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "jwt")
public record JwtProperties(
        /** HMAC-SHA key; at least 256 bits. */
        @NotBlank @Size(min = 32) String secret,
        @Min(1) long expirationMinutes,
        @Min(1) long refreshExpirationMinutes
) {

    public Duration accessTokenTtl() {
        return Duration.ofMinutes(expirationMinutes);
    }

    public Duration refreshTokenTtl() {
        return Duration.ofMinutes(refreshExpirationMinutes);
    }
}
