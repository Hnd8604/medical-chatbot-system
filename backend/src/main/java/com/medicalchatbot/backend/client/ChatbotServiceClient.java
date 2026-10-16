package com.medicalchatbot.backend.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.medicalchatbot.backend.dto.chatbot.ChatbotChatRequest;
import com.medicalchatbot.backend.dto.chatbot.ChatbotChatResponse;
import com.medicalchatbot.backend.dto.request.PatientSearchRequest;
import com.medicalchatbot.backend.enums.PatientResource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;

/**
 * HTTP client of chatbot-service, the only way Spring reaches FHIR data. FHIR payloads are returned
 * as {@link JsonNode} because their shape belongs to chatbot-service and is passed to the UI as-is.
 *
 * <p>Errors surface as {@code RestClientException}s and are translated by the global exception
 * handler.</p>
 */
@Component
public class ChatbotServiceClient {

    private final RestClient restClient;

    public ChatbotServiceClient(@Qualifier("chatbotRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    public ChatbotChatResponse chat(ChatbotChatRequest request) {
        return restClient.post()
                .uri("/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(ChatbotChatResponse.class);
    }

    public JsonNode getFhirStatus() {
        return restClient.get().uri("/fhir/status").retrieve().body(JsonNode.class);
    }

    public JsonNode searchPatients(PatientSearchRequest search) {
        return restClient.get()
                .uri(uriBuilder -> {
                    UriBuilder builder = uriBuilder.path("/patients").queryParam("limit", search.limit());
                    search.criteria().forEach(builder::queryParam);
                    return builder.build();
                })
                .retrieve()
                .body(JsonNode.class);
    }

    public JsonNode getPatient(String patientId) {
        return restClient.get().uri("/patients/{patientId}", patientId).retrieve().body(JsonNode.class);
    }

    public JsonNode getPatientResources(String patientId, PatientResource resource, int limit) {
        return restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/patients/{patientId}/" + resource.path())
                        .queryParam("limit", limit)
                        .build(patientId))
                .retrieve()
                .body(JsonNode.class);
    }
}
