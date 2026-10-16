package com.medicalchatbot.backend.config.properties;

import java.time.Duration;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "chatbot.service")
public record ChatbotServiceProperties(
        @NotBlank String baseUrl,
        @DefaultValue("3s") @NotNull Duration connectTimeout,
        /** A chat turn may run several LLM calls, so the read timeout is generous. */
        @DefaultValue("120s") @NotNull Duration readTimeout
) {
}
