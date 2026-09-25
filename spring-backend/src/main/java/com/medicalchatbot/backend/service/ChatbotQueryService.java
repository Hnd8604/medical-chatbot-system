package com.medicalchatbot.backend.service;

import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medicalchatbot.backend.dto.response.JsonPayload;
import com.medicalchatbot.backend.integration.client.ChatbotServiceClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Application service for read-only chatbot and patient-data queries. */
@Service
@RequiredArgsConstructor
public class ChatbotQueryService {

    private static final TypeReference<Map<String, Object>> JSON_OBJECT_TYPE = new TypeReference<>() {
    };

    private final ChatbotServiceClient chatbotServiceClient;
    private final ObjectMapper objectMapper;

    public JsonPayload<Map<String, Object>> status() {
        return payload(chatbotServiceClient.getStatus());
    }

    public JsonPayload<Map<String, Object>> searchPatients(
            String name,
            String phone,
            String birthDate,
            String identifier,
            int limit
    ) {
        return payload(chatbotServiceClient.searchPatients(name, phone, birthDate, identifier, limit));
    }

    public JsonPayload<Map<String, Object>> patient(String patientId) {
        return payload(chatbotServiceClient.getPatient(patientId));
    }

    public JsonPayload<Map<String, Object>> observations(String patientId, int limit) {
        return payload(chatbotServiceClient.getPatientObservations(patientId, limit));
    }

    public JsonPayload<Map<String, Object>> conditions(String patientId, int limit) {
        return payload(chatbotServiceClient.getPatientConditions(patientId, limit));
    }

    public JsonPayload<Map<String, Object>> encounters(String patientId, int limit) {
        return payload(chatbotServiceClient.getPatientEncounters(patientId, limit));
    }

    public JsonPayload<Map<String, Object>> medications(String patientId, int limit) {
        return payload(chatbotServiceClient.getPatientMedications(patientId, limit));
    }

    private JsonPayload<Map<String, Object>> payload(JsonNode response) {
        Map<String, Object> value = response == null || response.isNull()
                ? null
                : objectMapper.convertValue(response, JSON_OBJECT_TYPE);
        return new JsonPayload<>(value);
    }
}
