package com.medicalchatbot.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.medicalchatbot.backend.client.ChatbotServiceClient;
import com.medicalchatbot.backend.dto.request.patient.PatientSearchRequest;
import com.medicalchatbot.backend.dto.response.patient.SelfPatientProfileResponse;
import com.medicalchatbot.backend.entity.User;
import com.medicalchatbot.backend.enums.PatientResource;
import com.medicalchatbot.backend.exception.AppException;
import com.medicalchatbot.backend.exception.ErrorCode;
import com.medicalchatbot.backend.repository.UserRepository;
import com.medicalchatbot.backend.util.SecurityUtils;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

/**
 * Read-only FHIR data, fetched through chatbot-service. Staff endpoints take any patient id;
 * {@link #currentUserProfile()} only ever returns the caller's own linked record.
 */
@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class PatientService {

    /** Sizes of the sections of the patient panel. */
    private static final int RECENT_ENCOUNTERS = 5;
    private static final int RECENT_OBSERVATIONS = 5;
    private static final int CONDITIONS = 20;
    private static final int MEDICATIONS = 20;

    ChatbotServiceClient chatbotServiceClient;
    PatientScopeService patientScopeService;
    UserRepository userRepository;

    public JsonNode fhirStatus() {
        return chatbotServiceClient.getFhirStatus();
    }

    @PreAuthorize("hasAnyRole('DOCTOR', 'ADMIN')")
    public JsonNode searchPatients(PatientSearchRequest search) {
        return chatbotServiceClient.searchPatients(search);
    }

    @PreAuthorize("hasAnyRole('DOCTOR', 'ADMIN')")
    public JsonNode getPatient(String patientId) {
        return chatbotServiceClient.getPatient(patientId);
    }

    @PreAuthorize("hasAnyRole('DOCTOR', 'ADMIN')")
    public JsonNode getResources(String patientId, PatientResource resource, int limit) {
        return chatbotServiceClient.getPatientResources(patientId, resource, limit);
    }

    /** The signed-in user's own record (primary link first); the client cannot choose the patient. */
    public SelfPatientProfileResponse currentUserProfile() {
        User user = userRepository.findById(SecurityUtils.currentUserId())
                .orElseThrow(() -> new AppException(ErrorCode.ACCOUNT_NOT_EXISTED));
        String patientId = patientScopeService.linkedPatientIds(user).stream()
                .findFirst()
                .orElseThrow(() -> new AppException(ErrorCode.PATIENT_PROFILE_NOT_LINKED));
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
