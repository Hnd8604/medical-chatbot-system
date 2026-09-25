package com.medicalchatbot.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.medicalchatbot.backend.entity.Alert;
import com.medicalchatbot.backend.enums.AlertSeverity;
import com.medicalchatbot.backend.enums.AlertStatus;
import com.medicalchatbot.backend.integration.notification.TelegramAlertClient;
import com.medicalchatbot.backend.mapper.AlertMapper;
import com.medicalchatbot.backend.repository.AlertRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AlertServiceTest {

    @Mock
    private AlertRepository alertRepository;

    @Mock
    private AlertMapper alertMapper;

    @Mock
    private TelegramAlertClient telegramAlertClient;

    @Test
    void triggerAlertPersistsAndDelegatesOutboundDelivery() {
        when(alertRepository.existsByAlertTypeAndStatusAndCreatedAtAfter(
                eq("CHATBOT_DOWN"), eq(AlertStatus.OPEN), any()
        )).thenReturn(false);
        AlertService service = new AlertService(alertRepository, alertMapper, telegramAlertClient);

        service.triggerAlert(
                "AI_SERVICE",
                "CHATBOT_DOWN",
                AlertSeverity.CRITICAL,
                "Không thể kết nối chatbot.",
                null
        );

        ArgumentCaptor<Alert> alertCaptor = ArgumentCaptor.forClass(Alert.class);
        verify(alertRepository).save(alertCaptor.capture());
        assertEquals("AI_SERVICE", alertCaptor.getValue().getSource());
        assertEquals(AlertStatus.OPEN, alertCaptor.getValue().getStatus());
        assertEquals(JsonNodeFactory.instance.objectNode(), alertCaptor.getValue().getMetadataJson());
        verify(telegramAlertClient).send(
                "CHATBOT_DOWN",
                AlertSeverity.CRITICAL,
                "Không thể kết nối chatbot."
        );
    }

    @Test
    void triggerAlertSuppressesRecentDuplicate() {
        when(alertRepository.existsByAlertTypeAndStatusAndCreatedAtAfter(
                eq("CHATBOT_DOWN"), eq(AlertStatus.OPEN), any()
        )).thenReturn(true);
        AlertService service = new AlertService(alertRepository, alertMapper, telegramAlertClient);

        service.triggerAlert(
                "AI_SERVICE",
                "CHATBOT_DOWN",
                AlertSeverity.WARNING,
                "Repeated alert",
                null
        );

        verify(alertRepository, never()).save(any());
        verify(telegramAlertClient, never()).send(any(), any(), any());
    }
}
