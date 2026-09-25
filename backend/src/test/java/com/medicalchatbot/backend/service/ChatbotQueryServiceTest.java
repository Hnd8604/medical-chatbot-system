package com.medicalchatbot.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medicalchatbot.backend.dto.response.JsonPayload;
import com.medicalchatbot.backend.integration.client.ChatbotServiceClient;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ChatbotQueryServiceTest {

    @Mock
    private ChatbotServiceClient chatbotServiceClient;

    private ObjectMapper objectMapper;
    private ChatbotQueryService chatbotQueryService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        chatbotQueryService = new ChatbotQueryService(chatbotServiceClient, objectMapper);
    }

    @Test
    void patientDelegatesToClientAndPreservesJsonShape() throws Exception {
        JsonNode upstreamResponse = objectMapper.readTree("""
                {
                  "id": "BN2026-00001",
                  "name": "Van A Nguyen",
                  "telecom": [
                    {"system": "phone", "value": "0901234567"}
                  ]
                }
                """);
        when(chatbotServiceClient.getPatient("BN2026-00001")).thenReturn(upstreamResponse);

        JsonPayload<Map<String, Object>> response = chatbotQueryService.patient("BN2026-00001");

        assertEquals(upstreamResponse, objectMapper.readTree(objectMapper.writeValueAsString(response)));
        verify(chatbotServiceClient).getPatient("BN2026-00001");
    }

    @Test
    void searchPatientsForwardsEveryFilter() throws Exception {
        JsonNode upstreamResponse = objectMapper.readTree("""
                {"patients": [], "total": 0}
                """);
        when(chatbotServiceClient.searchPatients(
                "Nguyen Van A",
                "0901234567",
                "2003-01-01",
                "CCCD-001",
                25
        )).thenReturn(upstreamResponse);

        JsonPayload<Map<String, Object>> response = chatbotQueryService.searchPatients(
                "Nguyen Van A",
                "0901234567",
                "2003-01-01",
                "CCCD-001",
                25
        );

        assertEquals(upstreamResponse, objectMapper.readTree(objectMapper.writeValueAsString(response)));
        verify(chatbotServiceClient).searchPatients(
                "Nguyen Van A",
                "0901234567",
                "2003-01-01",
                "CCCD-001",
                25
        );
    }
}
