package com.medicalchatbot.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.medicalchatbot.backend.client.TelegramClient;
import com.medicalchatbot.backend.entity.Alert;
import com.medicalchatbot.backend.enums.AlertSeverity;
import com.medicalchatbot.backend.enums.AlertStatus;
import com.medicalchatbot.backend.enums.AlertType;
import com.medicalchatbot.backend.exception.AppException;
import com.medicalchatbot.backend.mapper.AlertMapper;
import com.medicalchatbot.backend.repository.AlertRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AlertServiceTest {

    @Mock
    private AlertRepository alertRepository;

    @Mock
    private TelegramClient telegramClient;

    private AlertService service;

    @BeforeEach
    void setUp() {
        service = new AlertService(alertRepository, Mappers.getMapper(AlertMapper.class), telegramClient);
    }

    @Test
    void raisePersistsTheTypeDefaultsAndDelegatesDelivery() {
        when(alertRepository.existsByAlertTypeAndStatusAndCreatedAtAfter(
                eq("CHATBOT_UNAVAILABLE"), eq(AlertStatus.OPEN), any())).thenReturn(false);

        service.raise(AlertType.CHATBOT_UNAVAILABLE, "Không thể kết nối chatbot.", null);

        ArgumentCaptor<Alert> alert = ArgumentCaptor.forClass(Alert.class);
        verify(alertRepository).save(alert.capture());
        assertEquals("AI_SERVICE", alert.getValue().getSource());
        assertEquals(AlertSeverity.CRITICAL, alert.getValue().getSeverity());
        assertEquals(AlertStatus.OPEN, alert.getValue().getStatus());
        assertEquals(JsonNodeFactory.instance.objectNode(), alert.getValue().getMetadataJson());
        verify(telegramClient).sendAlert("CHATBOT_UNAVAILABLE", AlertSeverity.CRITICAL, "Không thể kết nối chatbot.");
    }

    @Test
    void raiseSuppressesRecentOpenDuplicate() {
        when(alertRepository.existsByAlertTypeAndStatusAndCreatedAtAfter(
                eq("QUOTA_EXCEEDED"), eq(AlertStatus.OPEN), any())).thenReturn(true);

        service.raise(AlertType.QUOTA_EXCEEDED, "Repeated alert", null);

        verify(alertRepository, never()).save(any());
        verify(telegramClient, never()).sendAlert(any(), any(), any());
    }

    @Test
    void resolveRecordsWhoResolvedAnOpenAlert() {
        Alert alert = Alert.builder().id(UUID.randomUUID()).alertType("X").status(AlertStatus.OPEN).build();
        when(alertRepository.findById(alert.getId())).thenReturn(Optional.of(alert));

        service.resolve(alert.getId(), "admin_demo");

        assertEquals(AlertStatus.RESOLVED, alert.getStatus());
        assertEquals("admin_demo", alert.getResolvedBy());
        assertNotNull(alert.getResolvedAt());
    }

    @Test
    void resolveUnknownAlertIsNotFound() {
        UUID id = UUID.randomUUID();
        when(alertRepository.findById(id)).thenReturn(Optional.empty());

        assertThrows(AppException.class, () -> service.resolve(id, "admin_demo"));
    }
}
