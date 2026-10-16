package com.medicalchatbot.backend.service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.medicalchatbot.backend.client.ChatbotServiceClient;
import com.medicalchatbot.backend.dto.request.LinkPatientRequest;
import com.medicalchatbot.backend.dto.response.AuthUserResponse;
import com.medicalchatbot.backend.entity.User;
import com.medicalchatbot.backend.entity.UserPatientLink;
import com.medicalchatbot.backend.enums.PatientRelationship;
import com.medicalchatbot.backend.enums.UserRole;
import com.medicalchatbot.backend.exception.AppException;
import com.medicalchatbot.backend.exception.ErrorCode;
import com.medicalchatbot.backend.mapper.UserMapper;
import com.medicalchatbot.backend.repository.UserPatientLinkRepository;
import com.medicalchatbot.backend.util.PatientIds;
import com.medicalchatbot.backend.util.TextUtils;
import lombok.RequiredArgsConstructor;
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
public class PatientLinkService {

    private static final String MISMATCH = "Thông tin xác minh hồ sơ không khớp.";

    private final CurrentUserService currentUserService;
    private final UserPatientLinkRepository userPatientLinkRepository;
    private final ChatbotServiceClient chatbotServiceClient;
    private final UserMapper userMapper;
    private final AuthAuditService authAudit;

    /** A USER account must link its own patient record before it can chat. */
    public boolean isOnboardingRequired(User user) {
        return user.getRole() == UserRole.USER && !userPatientLinkRepository.existsByUserId(user.getId());
    }

    @Transactional
    public AuthUserResponse linkCurrentUser(LinkPatientRequest request) {
        User user = currentUserService.requireCurrentUser();
        if (user.getRole() != UserRole.USER) {
            throw AppException.forbidden("Chỉ tài khoản USER mới cần liên kết hồ sơ bệnh nhân.");
        }
        Verification verification = Verification.of(request);

        List<UserPatientLink> existingLinks = userPatientLinkRepository.findSelfLinksByUserId(user.getId());
        if (!existingLinks.isEmpty()) {
            boolean samePatient = existingLinks.stream()
                    .anyMatch(link -> PatientIds.same(link.getFhirPatientId(), verification.patientId()));
            if (!samePatient) {
                throw AppException.conflict("Tài khoản đã được liên kết với hồ sơ FHIR khác.");
            }
            return userMapper.toAuthUserResponse(user, false);
        }

        if (!verification.matches(fetchPatient(verification.patientId()))) {
            throw new AppException(ErrorCode.UNPROCESSABLE_ENTITY, MISMATCH);
        }
        if (userPatientLinkRepository.existsSelfLinkForOtherUser(verification.patientId(), user.getId())) {
            throw AppException.conflict("Hồ sơ bệnh nhân này đã được liên kết với tài khoản khác.");
        }

        try {
            userPatientLinkRepository.saveAndFlush(UserPatientLink.builder()
                    .user(user)
                    .fhirPatientId(verification.patientId())
                    .relationship(PatientRelationship.SELF)
                    .primaryLink(true)
                    .build());
        } catch (DataIntegrityViolationException exception) {
            throw new AppException(ErrorCode.CONFLICT, "Hồ sơ bệnh nhân này đã được liên kết.", exception);
        }

        authAudit.patientLinked(user, verification.patientId());
        return userMapper.toAuthUserResponse(user, false);
    }

    /** A missing patient or any 4xx is reported as a mismatch, so ids cannot be probed. */
    private JsonNode fetchPatient(String patientId) {
        try {
            return chatbotServiceClient.getPatient(patientId);
        } catch (HttpClientErrorException exception) {
            throw new AppException(ErrorCode.UNPROCESSABLE_ENTITY, MISMATCH, exception);
        } catch (RestClientResponseException exception) {
            throw new AppException(ErrorCode.UPSTREAM_SERVICE_ERROR,
                    "Không thể xác minh hồ sơ bệnh nhân từ FHIR service.", exception);
        } catch (RestClientException exception) {
            throw new AppException(ErrorCode.UPSTREAM_SERVICE_ERROR,
                    "Không thể kết nối FHIR service để xác minh hồ sơ.", exception);
        }
    }

    /** What the user claims about the record, normalized for comparison with FHIR. */
    private record Verification(String patientId, String birthDate, String phone) {

        static Verification of(LinkPatientRequest request) {
            String patientId = PatientIds.normalize(request.patientId());
            if (patientId == null) {
                throw AppException.invalid("Mã bệnh nhân không hợp lệ.");
            }
            String birthDate = TextUtils.strip(request.birthDate());
            try {
                LocalDate.parse(birthDate);
            } catch (DateTimeParseException exception) {
                throw AppException.invalid("Ngày sinh phải có định dạng YYYY-MM-DD.");
            }
            String phone = normalizePhone(request.phone());
            if (phone.length() < 8 || phone.length() > 15) {
                throw AppException.invalid("Số điện thoại không hợp lệ.");
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
