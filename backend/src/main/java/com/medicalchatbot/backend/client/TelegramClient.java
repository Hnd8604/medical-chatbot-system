package com.medicalchatbot.backend.client;

import java.util.Map;

import com.medicalchatbot.backend.config.properties.TelegramProperties;
import com.medicalchatbot.backend.enums.AlertSeverity;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Sends operational alerts to a Telegram chat when a bot is configured; otherwise does nothing. */
@Slf4j
@Component
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class TelegramClient {

    @Qualifier("telegramRestClient")
    RestClient restClient;
    TelegramProperties properties;
    @Qualifier(TaskExecutionAutoConfiguration.APPLICATION_TASK_EXECUTOR_BEAN_NAME)
    TaskExecutor taskExecutor;

    /** Fire-and-forget: alerting must never slow down or fail the request that raised the alert. */
    public void sendAlert(String type, AlertSeverity severity, String message) {
        if (!properties.enabled()) {
            return;
        }
        String text = String.format("%s [MEDICAL SYSTEM]%nMức độ: %s%nLoại: %s%nChi tiết: %s",
                emoji(severity), severity.name(), type, message);
        taskExecutor.execute(() -> sendMessage(text));
    }

    private void sendMessage(String text) {
        try {
            restClient.post()
                    .uri("/bot{token}/sendMessage", properties.botToken())
                    .body(Map.of("chat_id", properties.chatId(), "text", text))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RuntimeException exception) {
            // Never log the request URL: it contains the bot token.
            log.error("Không thể gửi cảnh báo Telegram ({})", exception.getClass().getSimpleName());
        }
    }

    private static String emoji(AlertSeverity severity) {
        return switch (severity) {
            case CRITICAL -> "🆘";
            case WARNING -> "⚠️";
            case INFO -> "ℹ️";
        };
    }
}
