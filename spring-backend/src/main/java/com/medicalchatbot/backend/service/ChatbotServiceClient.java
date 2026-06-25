package com.medicalchatbot.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.medicalchatbot.backend.dto.request.ChatbotChatRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@RequiredArgsConstructor
public class ChatbotServiceClient {

    private final RestClient chatbotRestClient;

    public JsonNode getStatus() {
        return chatbotRestClient.get()
                .uri("/fhir/status")
                .retrieve()
                .body(JsonNode.class);
    }

    public JsonNode getPatient(String patientId) {
        return chatbotRestClient.get()
                .uri("/patients/{patientId}", patientId)
                .retrieve()
                .body(JsonNode.class);
    }

    public JsonNode searchPatients(String name, String phone, String birthDate, String identifier, int limit) {
        return chatbotRestClient.get()
                .uri(uriBuilder -> {
                    var builder = uriBuilder
                            .path("/patients")
                            .queryParam("limit", limit);
                    if (name != null && !name.isBlank()) {
                        builder.queryParam("name", name);
                    }
                    if (phone != null && !phone.isBlank()) {
                        builder.queryParam("phone", phone);
                    }
                    if (birthDate != null && !birthDate.isBlank()) {
                        builder.queryParam("birth_date", birthDate);
                    }
                    if (identifier != null && !identifier.isBlank()) {
                        builder.queryParam("identifier", identifier);
                    }
                    return builder.build();
                })
                .retrieve()
                .body(JsonNode.class);
    }

    public JsonNode getPatientObservations(String patientId, int limit) {
        return chatbotRestClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/patients/{patientId}/observations")
                        .queryParam("limit", limit)
                        .build(patientId))
                .retrieve()
                .body(JsonNode.class);
    }

    public JsonNode getPatientConditions(String patientId, int limit) {
        return chatbotRestClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/patients/{patientId}/conditions")
                        .queryParam("limit", limit)
                        .build(patientId))
                .retrieve()
                .body(JsonNode.class);
    }

    public JsonNode getPatientEncounters(String patientId, int limit) {
        return chatbotRestClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/patients/{patientId}/encounters")
                        .queryParam("limit", limit)
                        .build(patientId))
                .retrieve()
                .body(JsonNode.class);
    }

    public JsonNode getPatientMedications(String patientId, int limit) {
        return chatbotRestClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/patients/{patientId}/medications")
                        .queryParam("limit", limit)
                        .build(patientId))
                .retrieve()
                .body(JsonNode.class);
    }

    public JsonNode chat(ChatbotChatRequest request) {
        return chatbotRestClient.post()
                .uri("/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(JsonNode.class);
    }
}
