package com.medicalchatbot.backend.service;

import java.util.UUID;

import com.medicalchatbot.backend.dto.response.SelfPatientProfileResponse;
import com.medicalchatbot.backend.exception.AppException;
import com.medicalchatbot.backend.exception.ErrorCode;
import com.medicalchatbot.backend.integration.client.ChatbotServiceClient;
import com.medicalchatbot.backend.repository.UserPatientLinkRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Truy xuất hồ sơ FHIR của chính người dùng (SELF scope).
 * Patient id được suy ra từ liên kết của tài khoản hiện tại — client KHÔNG được
 * truyền patient id, nên không thể xem hồ sơ của bệnh nhân khác.
 */
@Service
@RequiredArgsConstructor
public class SelfPatientService {

    private final CurrentUserService currentUserService;
    private final UserPatientLinkRepository userPatientLinkRepository;
    private final ChatbotServiceClient chatbotServiceClient;

    /**
     * Hồ sơ FHIR đã liên kết với tài khoản hiện tại (primary link trước).
     */
    public SelfPatientProfileResponse currentUserProfile() {
        String patientId = resolveLinkedPatientId();
        return new SelfPatientProfileResponse(
                patientId,
                chatbotServiceClient.getPatient(patientId),
                chatbotServiceClient.getPatientEncounters(patientId, 5),
                chatbotServiceClient.getPatientObservations(patientId, 5),
                chatbotServiceClient.getPatientConditions(patientId, 20),
                chatbotServiceClient.getPatientMedications(patientId, 20)
        );
    }

    private String resolveLinkedPatientId() {
        UUID userId = currentUserService.requireCurrentUserId();
        return userPatientLinkRepository.findPatientIdsForUser(userId).stream()
                .map(UserPatientScopeService::normalizePatientId)
                .filter(id -> id != null && !id.isBlank())
                .findFirst()
                .orElseThrow(() -> new AppException(
                        ErrorCode.RESOURCE_NOT_FOUND,
                        "Tai khoan chua duoc lien ket voi ho so FHIR nao."
                ));
    }
}
