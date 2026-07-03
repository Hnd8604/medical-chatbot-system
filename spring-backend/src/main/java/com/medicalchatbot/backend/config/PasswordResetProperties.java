package com.medicalchatbot.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * Cấu hình luồng quên mật khẩu (OTP + ticket lưu trong Redis, email gửi mã).
 */
@Validated
@ConfigurationProperties(prefix = "password-reset")
public record PasswordResetProperties(
        @Min(1) long otpTtlMinutes,
        @Min(1) long ticketTtlMinutes,
        @Min(1) int maxAttempts,
        @Min(0) long resendCooldownSeconds,
        @NotBlank String fromAddress,
        @NotBlank String fromName
) {
}
