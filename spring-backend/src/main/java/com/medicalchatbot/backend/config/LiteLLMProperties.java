package com.medicalchatbot.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cau hinh AI Gateway (LiteLLM). Spring dung master key de quan ly virtual key
 * theo user va doc spend/budget. Khi {@code enabled=false} hoac thieu master key,
 * he thong bo qua gateway va chatbot-service tu roi ve master key mac dinh.
 */
@ConfigurationProperties(prefix = "litellm")
public record LiteLLMProperties(
        String baseUrl,
        String masterKey,
        Boolean enabled
) {
    public boolean isEnabled() {
        return Boolean.TRUE.equals(enabled) && masterKey != null && !masterKey.isBlank();
    }
}
