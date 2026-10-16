package com.medicalchatbot.backend.service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.medicalchatbot.backend.client.TelegramClient;
import com.medicalchatbot.backend.dto.response.PageResponse;
import com.medicalchatbot.backend.dto.response.admin.AlertResponse;
import com.medicalchatbot.backend.entity.Alert;
import com.medicalchatbot.backend.enums.AlertSeverity;
import com.medicalchatbot.backend.enums.AlertStatus;
import com.medicalchatbot.backend.enums.AlertType;
import com.medicalchatbot.backend.exception.AppException;
import com.medicalchatbot.backend.exception.ErrorCode;
import com.medicalchatbot.backend.mapper.AlertMapper;
import com.medicalchatbot.backend.repository.AlertRepository;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Operational alerts for administrators, mirrored to Telegram when configured. */
@Slf4j
@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class AlertService {

    /** While an alert of a type is still open, new ones of that type are dropped for this long. */
    private static final Duration SUPPRESSION_WINDOW = Duration.ofMinutes(15);

    AlertRepository alertRepository;
    AlertMapper alertMapper;
    TelegramClient telegramClient;

    /**
     * Records an incident. It commits independently: the operation that hit the incident usually
     * fails right after, and its rollback must not erase the alert.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void raise(AlertType type, String message, JsonNode metadata) {
        OffsetDateTime since = OffsetDateTime.now().minus(SUPPRESSION_WINDOW);
        if (alertRepository.existsByAlertTypeAndStatusAndCreatedAtAfter(type.name(), AlertStatus.OPEN, since)) {
            log.debug("Alert {} suppressed: an open one was raised less than {} ago", type, SUPPRESSION_WINDOW);
            return;
        }

        alertRepository.save(Alert.builder()
                .source(type.source())
                .alertType(type.name())
                .severity(type.severity())
                .message(message)
                .metadataJson(metadata != null ? metadata : JsonNodeFactory.instance.objectNode())
                .build());
        log.warn("[SYSTEM ALERT - {}] {}: {}", type.severity(), type, message);
        telegramClient.sendAlert(type.name(), type.severity(), message);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    public PageResponse<AlertResponse> search(
            AlertStatus status,
            AlertSeverity severity,
            String source,
            String alertType,
            OffsetDateTime fromDate,
            OffsetDateTime toDate,
            Pageable pageable
    ) {
        return PageResponse.of(
                alertRepository.search(status, severity, source, alertType, fromDate, toDate, pageable),
                alertMapper::toAlertResponse
        );
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public void resolve(UUID alertId, String resolvedBy) {
        Alert alert = alertRepository.findById(alertId)
                .orElseThrow(() -> new AppException(ErrorCode.ALERT_NOT_EXISTED));
        alert.resolve(resolvedBy);
        log.info("Alert {} resolved by {}", alertId, resolvedBy);
    }
}
