package com.medicalchatbot.backend.service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.medicalchatbot.backend.dto.request.AuthLinkPatientRequest;
import com.medicalchatbot.backend.dto.response.AuthLinkPatientResponse;
import com.medicalchatbot.backend.entity.User;
import com.medicalchatbot.backend.entity.UserPatientLink;
import com.medicalchatbot.backend.enums.UserRole;
import com.medicalchatbot.backend.exception.AppException;
import com.medicalchatbot.backend.exception.ErrorCode;
import com.medicalchatbot.backend.integration.client.ChatbotServiceClient;
import com.medicalchatbot.backend.mapper.AuthUserMapper;
import com.medicalchatbot.backend.repository.UserPatientLinkRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** Handles verification and persistence of a user's self-patient link. */
@Service
@RequiredArgsConstructor
public class PatientLinkService {

    private final CurrentUserService currentUserService;
    private final UserPatientLinkRepository userPatientLinkRepository;
    private final ChatbotServiceClient chatbotServiceClient;
    private final AuthUserMapper authUserMapper;
    private final AuthAuditService authAuditService;

    @Transactional
    public AuthLinkPatientResponse linkCurrentUser(AuthLinkPatientRequest request) {
        User user = currentUserService.requireCurrentUser();
        if (user.getRole() != UserRole.USER) {
            throw new AppException(ErrorCode.ACCESS_DENIED, "Chỉ tài khoản USER mới cần liên kết hồ sơ bệnh nhân.");
        }

        LinkPatientInput input = normalize(request);
        validate(input);

        List<UserPatientLink> existingLinks = userPatientLinkRepository.findSelfLinksForUser(user.getId());
        if (!existingLinks.isEmpty()) {
            boolean alreadyLinkedToSamePatient = existingLinks.stream()
                    .anyMatch(link -> samePatientId(link.getFhirPatientId(), input.patientId()));
            if (alreadyLinkedToSamePatient) {
                return new AuthLinkPatientResponse("Hồ sơ bệnh nhân đã được liên kết.", authUserMapper.toResponse(user));
            }
            throw new AppException(ErrorCode.CONFLICT, "Tài khoản đã được liên kết với hồ sơ FHIR khác.");
        }

        JsonNode patient = getPatient(input.patientId());
        if (!matches(patient, input)) {
            throw new AppException(ErrorCode.UNPROCESSABLE_ENTITY, "Thông tin xác minh hồ sơ không khớp.");
        }
        if (userPatientLinkRepository.existsSelfLinkForOtherUser(input.patientId(), user.getId())) {
            throw new AppException(ErrorCode.CONFLICT, "Hồ sơ bệnh nhân này đã được liên kết với tài khoản khác.");
        }

        UserPatientLink link = UserPatientLink.builder()
                .user(user)
                .fhirPatientId(input.patientId())
                .relationship("SELF")
                .primaryLink(true)
                .build();
        try {
            userPatientLinkRepository.saveAndFlush(link);
        } catch (DataIntegrityViolationException ex) {
            throw new AppException(ErrorCode.CONFLICT, "Hồ sơ bệnh nhân này đã được liên kết.", ex);
        }

        authAuditService.patientLinked(user, input.patientId());
        return new AuthLinkPatientResponse("Liên kết hồ sơ bệnh nhân thành công.", authUserMapper.toResponse(user));
    }

    private LinkPatientInput normalize(AuthLinkPatientRequest request) {
        return new LinkPatientInput(
                normalizePatientId(request.patientId()),
                strip(request.birthDate()),
                normalizePhone(request.phone())
        );
    }

    private void validate(LinkPatientInput input) {
        if (input.patientId() == null || input.patientId().isBlank()) {
            throw new AppException(ErrorCode.INVALID_ARGUMENT, "Mã bệnh nhân không hợp lệ.");
        }
        try {
            LocalDate.parse(input.birthDate());
        } catch (DateTimeParseException ex) {
            throw new AppException(ErrorCode.INVALID_ARGUMENT, "Ngày sinh phải có định dạng YYYY-MM-DD.", ex);
        }
        if (input.phone() == null || input.phone().length() < 8 || input.phone().length() > 15) {
            throw new AppException(ErrorCode.INVALID_ARGUMENT, "Số điện thoại không hợp lệ.");
        }
    }

    private JsonNode getPatient(String patientId) {
        try {
            return chatbotServiceClient.getPatient(patientId);
        } catch (HttpClientErrorException.NotFound ex) {
            throw new AppException(ErrorCode.UNPROCESSABLE_ENTITY, "Thông tin xác minh hồ sơ không khớp.", ex);
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().is4xxClientError()) {
                throw new AppException(ErrorCode.UNPROCESSABLE_ENTITY, "Thông tin xác minh hồ sơ không khớp.", ex);
            }
            throw new AppException(ErrorCode.UPSTREAM_SERVICE_ERROR, "Không thể xác minh hồ sơ bệnh nhân từ FHIR service.", ex);
        } catch (RestClientException ex) {
            throw new AppException(ErrorCode.UPSTREAM_SERVICE_ERROR, "Không thể kết nối FHIR service để xác minh hồ sơ.", ex);
        }
    }

    private boolean matches(JsonNode patient, LinkPatientInput input) {
        if (patient == null || patient.isNull()) {
            return false;
        }
        return samePatientId(text(patient, "id"), input.patientId())
                && input.birthDate().equals(text(patient, "birth_date", "birthDate"))
                && phoneMatches(patient, input.phone());
    }

    private boolean phoneMatches(JsonNode patient, String expectedPhone) {
        if (expectedPhone.equals(normalizePhone(text(patient, "phone")))) {
            return true;
        }
        JsonNode telecom = patient.path("telecom");
        if (!telecom.isArray()) {
            return false;
        }
        for (JsonNode item : telecom) {
            if ("phone".equalsIgnoreCase(text(item, "system"))
                    && expectedPhone.equals(normalizePhone(text(item, "value")))) {
                return true;
            }
        }
        return false;
    }

    private boolean samePatientId(String left, String right) {
        String normalizedLeft = normalizePatientId(left);
        String normalizedRight = normalizePatientId(right);
        return normalizedLeft != null && normalizedLeft.equalsIgnoreCase(normalizedRight);
    }

    private static String normalizePatientId(String value) {
        return UserPatientScopeService.normalizePatientId(value);
    }

    private static String normalizePhone(String value) {
        String phone = strip(value).replaceAll("[\\s().-]", "");
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

    private static String strip(String value) {
        return value == null ? "" : value.strip();
    }

    private record LinkPatientInput(String patientId, String birthDate, String phone) {
    }
}
