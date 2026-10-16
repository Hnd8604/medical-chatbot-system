package com.medicalchatbot.backend.service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.medicalchatbot.backend.client.ChatbotServiceClient;
import com.medicalchatbot.backend.dto.request.patient.LinkPatientRequest;
import com.medicalchatbot.backend.dto.response.auth.AuthUserResponse;
import com.medicalchatbot.backend.entity.User;
import com.medicalchatbot.backend.entity.UserPatientLink;
import com.medicalchatbot.backend.enums.PatientRelationship;
import com.medicalchatbot.backend.enums.UserRole;
import com.medicalchatbot.backend.exception.AppException;
import com.medicalchatbot.backend.exception.ErrorCode;
import com.medicalchatbot.backend.mapper.UserMapper;
import com.medicalchatbot.backend.repository.UserPatientLinkRepository;
import com.medicalchatbot.backend.repository.UserRepository;
import com.medicalchatbot.backend.util.PatientIds;
import com.medicalchatbot.backend.util.SecurityUtils;
import com.medicalchatbot.backend.util.TextUtils;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Onboarding of USER accounts: proves ownership of a FHIR patient record (birth date + phone) and
 * links it to the account as {@link PatientRelationship#SELF}.
 */
@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class PatientLinkService {

    UserRepository userRepository;
    UserPatientLinkRepository userPatientLinkRepository;
    ChatbotServiceClient chatbotServiceClient;
    UserMapper userMapper;
    AuthAuditService authAudit;

    /** A USER account must link its own patient record before it can chat. */
    public boolean isOnboardingRequired(User user) {
        return user.getRole() == UserRole.USER && !userPatientLinkRepository.existsByUserId(user.getId());
    }

    @Transactional
    public AuthUserResponse linkCurrentUser(LinkPatientRequest request) {
        User user = userRepository.findById(SecurityUtils.currentUserId())
                .orElseThrow(() -> new AppException(ErrorCode.ACCOUNT_NOT_EXISTED));
        if (user.getRole() != UserRole.USER) {
            throw new AppException(ErrorCode.PATIENT_LINK_NOT_ALLOWED);
        }
        Verification verification = Verification.of(request);

        List<UserPatientLink> existingLinks = userPatientLinkRepository.findSelfLinksByUserId(user.getId());
        if (!existingLinks.isEmpty()) {
            boolean samePatient = existingLinks.stream()
                    .anyMatch(link -> PatientIds.same(link.getFhirPatientId(), verification.patientId()));
            if (!samePatient) {
                throw new AppException(ErrorCode.ACCOUNT_LINKED_TO_OTHER_PATIENT);
            }
            return userMapper.toAuthUserResponse(user, false);
        }

        if (!verification.matches(fetchPatient(verification.patientId()))) {
            throw new AppException(ErrorCode.PATIENT_VERIFICATION_FAILED);
        }
        if (userPatientLinkRepository.existsSelfLinkForOtherUser(verification.patientId(), user.getId())) {
            throw new AppException(ErrorCode.PATIENT_LINKED_TO_OTHER_ACCOUNT);
        }

        try {
            userPatientLinkRepository.saveAndFlush(UserPatientLink.builder()
                    .user(user)
                    .fhirPatientId(verification.patientId())
                    .relationship(PatientRelationship.SELF)
                    .primaryLink(true)
                    .build());
        } catch (DataIntegrityViolationException exception) {
            throw new AppException(ErrorCode.PATIENT_LINKED_TO_OTHER_ACCOUNT, exception);
        }

        authAudit.patientLinked(user, verification.patientId());
        return userMapper.toAuthUserResponse(user, false);
    }

    /** A missing patient or any 4xx is reported as a mismatch, so ids cannot be probed. */
    private JsonNode fetchPatient(String patientId) {
        try {
            return chatbotServiceClient.getPatient(patientId);
        } catch (HttpClientErrorException exception) {
            throw new AppException(ErrorCode.PATIENT_VERIFICATION_FAILED, exception);
        } catch (RestClientResponseException exception) {
            throw new AppException(ErrorCode.PATIENT_VERIFICATION_UNAVAILABLE, exception);
        } catch (RestClientException exception) {
            throw new AppException(ErrorCode.PATIENT_VERIFICATION_UNAVAILABLE, exception);
        }
    }

    /** What the user claims about the record, normalized for comparison with FHIR. */
    private record Verification(String patientId, String birthDate, String phone) {

        static Verification of(LinkPatientRequest request) {
            String patientId = PatientIds.normalize(request.patientId());
            if (patientId == null) {
                throw new AppException(ErrorCode.PATIENT_ID_INVALID);
            }
            String birthDate = TextUtils.strip(request.birthDate());
            try {
                LocalDate.parse(birthDate);
            } catch (DateTimeParseException exception) {
                throw new AppException(ErrorCode.BIRTH_DATE_INVALID);
            }
            String phone = normalizePhone(request.phone());
            if (phone.length() < 8 || phone.length() > 15) {
                throw new AppException(ErrorCode.PHONE_INVALID);
            }
            return new Verification(patientId, birthDate, phone);
        }

        boolean matches(JsonNode patient) {
            if (patient == null || patient.isNull()) {
                return false;
            }
            return PatientIds.same(text(patient, "id"), patientId)
                    && birthDate.equals(text(patient, "birth_date", "birthDate"))
                    && hasPhone(patient);
        }

        private boolean hasPhone(JsonNode patient) {
            if (phone.equals(normalizePhone(text(patient, "phone")))) {
                return true;
            }
            for (JsonNode telecom : patient.path("telecom")) {
                if ("phone".equalsIgnoreCase(text(telecom, "system"))
                        && phone.equals(normalizePhone(text(telecom, "value")))) {
                    return true;
                }
            }
            return false;
        }

        /** Vietnamese numbers: drop separators and turn the +84/84 country code into a leading 0. */
        private static String normalizePhone(String value) {
            String phone = TextUtils.strip(value).replaceAll("[\\s().-]", "");
            if (phone.startsWith("+84")) {
                return "0" + phone.substring(3);
            }
            if (phone.startsWith("84") && phone.length() >= 10) {
                return "0" + phone.substring(2);
            }
            return phone;
        }

        private static String text(JsonNode node, String... fields) {
            for (String field : fields) {
                JsonNode value = node.get(field);
                if (value != null && value.isTextual() && !value.asText().isBlank()) {
                    return value.asText().strip();
                }
            }
            return null;
        }
    }
}
