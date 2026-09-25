package com.medicalchatbot.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.medicalchatbot.backend.entity.Alert;
import com.medicalchatbot.backend.dto.response.AlertResponse;
import com.medicalchatbot.backend.enums.AlertSeverity;
import com.medicalchatbot.backend.enums.AlertStatus;
import com.medicalchatbot.backend.exception.AppException;
import com.medicalchatbot.backend.exception.ErrorCode;
import com.medicalchatbot.backend.integration.notification.TelegramAlertClient;
import com.medicalchatbot.backend.repository.AlertRepository;
import com.medicalchatbot.backend.mapper.AlertMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.OffsetDateTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AlertService {

    private final AlertRepository alertRepository;
    private final AlertMapper alertMapper;
    private final TelegramAlertClient telegramAlertClient;

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

        telegramAlertClient.send(alertType, severity, message);

    }


    @Transactional(readOnly = true)
    public Page<AlertResponse> searchAlerts(
            AlertStatus status,
            AlertSeverity severity,
            String source,
            String alertType,
            OffsetDateTime fromDate,
            OffsetDateTime toDate,
            Pageable pageable
    ) {
        return alertRepository.searchAlerts(status, severity, source, alertType, fromDate, toDate, pageable)
                .map(alertMapper::toResponse);
    }


    @Transactional
    public void resolveAlert(UUID alertId, String resolvedBy) {
        Alert alert = alertRepository.findById(alertId)
                .orElseThrow(() -> new AppException(
                        ErrorCode.RESOURCE_NOT_FOUND,
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
}
