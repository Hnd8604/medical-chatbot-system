package com.medicalchatbot.backend.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Validated
@ConfigurationProperties(prefix = "chatbot.service")
public record ChatbotServiceProperties(
        @NotBlank String baseUrl,
        @DefaultValue("3s") @NotNull Duration connectTimeout,
        @DefaultValue("120s") @NotNull Duration readTimeout
) {
}
