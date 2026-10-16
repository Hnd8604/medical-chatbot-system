package com.medicalchatbot.backend.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Optional Telegram delivery of operational alerts; disabled while either value is blank. */
@ConfigurationProperties(prefix = "telegram")
public record TelegramProperties(String botToken, String chatId) {

    public boolean enabled() {
        return botToken != null && !botToken.isBlank() && chatId != null && !chatId.isBlank();
    }
}
