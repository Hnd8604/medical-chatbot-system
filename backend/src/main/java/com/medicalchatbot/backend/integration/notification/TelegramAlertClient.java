package com.medicalchatbot.backend.integration.notification;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.medicalchatbot.backend.enums.AlertSeverity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Outbound adapter for optional Telegram system-alert delivery. */
@Slf4j
@Component
public class TelegramAlertClient {

    private final RestClient restClient;

    @Value("${telegram.bot.token:}")
    private String botToken;

    @Value("${telegram.chat.id:}")
    private String chatId;

    public TelegramAlertClient(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder.baseUrl("https://api.telegram.org").build();
    }

    public void send(String type, AlertSeverity severity, String message) {
        if (botToken == null || botToken.isBlank() || chatId == null || chatId.isBlank()) {
            return;
        }

        String emoji = switch (severity) {
            case CRITICAL -> "🆘";
            case WARNING -> "⚠️";
            case INFO -> "ℹ️";
        };
        String text = String.format("%s [MEDICAL SYSTEM]%nMức độ: %s%nLoại: %s%nChi tiết: %s",
                emoji, severity.name(), type, message);

        CompletableFuture.runAsync(() -> sendMessage(text));
    }

    private void sendMessage(String text) {
        try {
            restClient.post()
                    .uri("/bot{token}/sendMessage", botToken)
                    .body(Map.of("chat_id", chatId, "text", text))
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception exception) {
            // Do not include the request URL or token in logs.
            log.error("Không thể gửi cảnh báo Telegram ({})", exception.getClass().getSimpleName());
        }
    }
}
