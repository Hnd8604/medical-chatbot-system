package com.medicalchatbot.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.medicalchatbot.backend.entity.Alert;
import com.medicalchatbot.backend.enums.AlertSeverity;
import com.medicalchatbot.backend.enums.AlertStatus;
import com.medicalchatbot.backend.repository.AlertRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.client.RestClient;
import java.util.concurrent.CompletableFuture;
import java.time.OffsetDateTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AlertService {

    private final AlertRepository alertRepository;


    @Value("${telegram.bot.token:}")
    private String botToken;

    @Value("${telegram.chat.id:}")
    private String chatId;

    private final RestClient restClient = RestClient.create();

    @Transactional
    public void triggerAlert(String source, String alertType, AlertSeverity severity, String message, JsonNode metadata) {
        OffsetDateTime thresholdTime = OffsetDateTime.now().minusMinutes(15);

        boolean isSpam = alertRepository.existsByAlertTypeAndStatusAndCreatedAtAfter(
                alertType, AlertStatus.OPEN, thresholdTime
        );

        if (isSpam) {
            log.debug("[ALERT SUPPRESSED] Alert {} từ {} đang spam, đã bỏ qua.", alertType, source);
            return;
        }

        JsonNode safeMetadata = metadata != null ? metadata : JsonNodeFactory.instance.objectNode();
        Alert alert = Alert.builder()
                .source(source)
                .alertType(alertType)
                .severity(severity)
                .status(AlertStatus.OPEN)
                .message(message)
                .metadataJson(safeMetadata)
                .build();

        alertRepository.save(alert);
        log.warn("[SYSTEM ALERT - {}] {}: {}", severity, alertType, message);

        sendTelegramNotification(alertType, severity, message);

    }


    @Transactional(readOnly = true)
    public Page<Alert> searchAlerts(
            AlertStatus status,
            AlertSeverity severity,
            String source,
            String alertType,
            OffsetDateTime fromDate,
            OffsetDateTime toDate,
            Pageable pageable
    ) {
        return alertRepository.searchAlerts(status, severity, source, alertType, fromDate, toDate, pageable);
    }


    @Transactional
    public void resolveAlert(UUID alertId, String resolvedBy) {
        Alert alert = alertRepository.findById(alertId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Không tìm thấy Alert với ID: " + alertId
                ));

        if (alert.getStatus() == AlertStatus.OPEN) {
            alert.setStatus(AlertStatus.RESOLVED);
            alert.setResolvedAt(OffsetDateTime.now());
            alert.setResolvedBy(resolvedBy);
            alertRepository.save(alert);
            log.info("Alert {} đã được đánh dấu RESOLVED bởi {}", alertId, resolvedBy);
        }
    }


    private void sendTelegramNotification(String type, AlertSeverity severity, String message) {
        if (botToken == null || botToken.isBlank() || chatId == null || chatId.isBlank()) {
            return;
        }

        String emoji = switch (severity) {
            case CRITICAL -> "🆘";
            case WARNING -> "⚠️";
            case INFO -> "ℹ️";
        };
        String text = String.format("%s [MEDICAL SYSTEM]\nMức độ: %s\nLoại: %s\nChi tiết: %s",
                emoji, severity.name(), type, message);

        String url = "https://api.telegram.org/bot" + botToken + "/sendMessage";
        CompletableFuture.runAsync(() -> {
            try {
                restClient.post()
                        .uri(url)
                        .body(java.util.Map.of("chat_id", chatId, "text", text))
                        .retrieve()
                        .toBodilessEntity();
            } catch (Exception e) {
                log.error(" Không thể gửi tin nhắn Telegram: {}", e.getMessage());
            }
        });
    }
}