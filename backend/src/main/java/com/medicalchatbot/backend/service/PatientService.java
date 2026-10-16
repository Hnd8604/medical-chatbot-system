package com.medicalchatbot.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.medicalchatbot.backend.client.ChatbotServiceClient;
import com.medicalchatbot.backend.enums.PatientResource;
import com.medicalchatbot.backend.dto.request.PatientSearchRequest;
import com.medicalchatbot.backend.dto.response.SelfPatientProfileResponse;
import com.medicalchatbot.backend.entity.User;
import com.medicalchatbot.backend.exception.AppException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Read-only FHIR data, fetched through chatbot-service. Staff endpoints take any patient id;
 * {@link #currentUserProfile()} only ever returns the caller's own linked record.
 */
@Service
@RequiredArgsConstructor
public class PatientService {

    /** Sizes of the sections of the patient panel. */
    private static final int RECENT_ENCOUNTERS = 5;
    private static final int RECENT_OBSERVATIONS = 5;
    private static final int CONDITIONS = 20;
    private static final int MEDICATIONS = 20;

    private final ChatbotServiceClient chatbotServiceClient;
    private final CurrentUserService currentUserService;
    private final PatientScopeService patientScopeService;

    public JsonNode fhirStatus() {
        return chatbotServiceClient.getFhirStatus();
    }

    public JsonNode searchPatients(PatientSearchRequest search) {
        return chatbotServiceClient.searchPatients(search);
    }

    public JsonNode getPatient(String patientId) {
        return chatbotServiceClient.getPatient(patientId);
    }

    public JsonNode getResources(String patientId, PatientResource resource, int limit) {
        return chatbotServiceClient.getPatientResources(patientId, resource, limit);
    }

    /** The signed-in user's own record (primary link first); the client cannot choose the patient. */
    public SelfPatientProfileResponse currentUserProfile() {
        User user = currentUserService.requireCurrentUser();
        String patientId = patientScopeService.linkedPatientIds(user).stream()
                .findFirst()
                .orElseThrow(() -> AppException.notFound("Tài khoản chưa được liên kết với hồ sơ FHIR nào."));
        return new SelfPatientProfileResponse(
                patientId,
                getPatient(patientId),
                getResources(patientId, PatientResource.ENCOUNTERS, RECENT_ENCOUNTERS),
                getResources(patientId, PatientResource.OBSERVATIONS, RECENT_OBSERVATIONS),
                getResources(patientId, PatientResource.CONDITIONS, CONDITIONS),
                getResources(patientId, PatientResource.MEDICATIONS, MEDICATIONS)
        );
    }
}
