package com.medicalchatbot.backend.config.properties;

import java.time.Duration;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Forgot-password flow: emailed OTP code, then a one-time reset ticket (both kept in Redis). */
@Validated
@ConfigurationProperties(prefix = "password-reset")
public record PasswordResetProperties(
        @Min(1) long otpTtlMinutes,
        @Min(1) long ticketTtlMinutes,
        /** Wrong codes allowed before the OTP is invalidated. */
        @Min(1) int maxAttempts,
        /** Minimum delay between two codes for the same email; 0 disables the cooldown. */
        @Min(0) long resendCooldownSeconds,
        @NotBlank String fromAddress,
        @NotBlank String fromName
) {

    public Duration otpTtl() {
        return Duration.ofMinutes(otpTtlMinutes);
    }

    public Duration ticketTtl() {
        return Duration.ofMinutes(ticketTtlMinutes);
    }

    public Duration resendCooldown() {
        return Duration.ofSeconds(resendCooldownSeconds);
    }
}
