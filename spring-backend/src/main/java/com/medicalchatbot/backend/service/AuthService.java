package com.medicalchatbot.backend.service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.medicalchatbot.backend.dto.request.AuthLoginRequest;
import com.medicalchatbot.backend.dto.request.AuthLinkPatientRequest;
import com.medicalchatbot.backend.dto.request.AuthRegisterRequest;
import com.medicalchatbot.backend.dto.response.AuthLinkPatientResponse;
import com.medicalchatbot.backend.dto.response.AuthLoginResponse;
import com.medicalchatbot.backend.dto.response.AuthRegisterResponse;
import com.medicalchatbot.backend.dto.response.AuthUserResponse;
import com.medicalchatbot.backend.entity.QuotaPolicy;
import com.medicalchatbot.backend.entity.User;
import com.medicalchatbot.backend.entity.UserPatientLink;
import com.medicalchatbot.backend.enums.UserRole;
import com.medicalchatbot.backend.enums.UserStatus;
import com.medicalchatbot.backend.repository.AuditLogRepository;
import com.medicalchatbot.backend.repository.QuotaPolicyRepository;
import com.medicalchatbot.backend.repository.UserPatientLinkRepository;
import com.medicalchatbot.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final UserPatientLinkRepository userPatientLinkRepository;
    private final QuotaPolicyRepository quotaPolicyRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService jwtTokenService;
    private final CurrentUserService currentUserService;
    private final ChatbotServiceClient chatbotServiceClient;
    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public AuthLoginResponse login(AuthLoginRequest request) {
        String credential = request.usernameOrEmail().strip();
        User user = userRepository.findByUsernameOrEmailIgnoreCase(credential)
                .orElseThrow(() -> invalidCredentials(credential));

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw invalidCredentials(credential);
        }
        if (user.getStatus() == UserStatus.LOCKED) {
            logLoginFailure(user, credential, "LOCKED");
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Tai khoan da bi khoa.");
        }
        if (user.getStatus() == UserStatus.DISABLED) {
            logLoginFailure(user, credential, "DISABLED");
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Tai khoan da bi vo hieu hoa.");
        }

        logLoginSuccess(user);
        return new AuthLoginResponse(
                jwtTokenService.generateToken(user),
                "Bearer",
                jwtTokenService.expiresInSeconds(),
                userResponse(user)
        );
    }

    @Transactional
    public AuthRegisterResponse register(AuthRegisterRequest request) {
        RegistrationInput input = normalizeRegistration(request);
        validateRegistration(input);

        if (userRepository.existsByUsernameIgnoreCase(input.username())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Tên đăng nhập đã được sử dụng.");
        }
        if (userRepository.existsByEmailIgnoreCase(input.email())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email đã được sử dụng.");
        }

        QuotaPolicy quotaPolicy = quotaPolicyRepository.findByName("free_demo")
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "Chưa cấu hình quota policy mặc định free_demo."
                ));

        User user = User.builder()
                .username(input.username())
                .email(input.email())
                .displayName(input.displayName())
                .passwordHash(passwordEncoder.encode(input.password()))
                .role(UserRole.USER)
                .status(UserStatus.ACTIVE)
                .tokenVersion(0)
                .quotaPolicy(quotaPolicy)
                .build();

        try {
            user = userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Tên đăng nhập hoặc email đã được sử dụng.");
        }

        logRegisterSuccess(user);
        return new AuthRegisterResponse("Đăng ký tài khoản thành công.", userResponse(user));
    }

    @Transactional(readOnly = true)
    public AuthUserResponse currentUser() {
        return userResponse(currentUserService.requireCurrentUser());
    }

    @Transactional
    public AuthLinkPatientResponse linkPatient(AuthLinkPatientRequest request) {
        User user = currentUserService.requireCurrentUser();
        if (user.getRole() != UserRole.USER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Chỉ tài khoản USER mới cần liên kết hồ sơ bệnh nhân.");
        }

        LinkPatientInput input = normalizeLinkPatient(request);
        validateLinkPatient(input);

        List<UserPatientLink> existingLinks = userPatientLinkRepository.findSelfLinksForUser(user.getId());
        if (!existingLinks.isEmpty()) {
            boolean alreadyLinkedToSamePatient = existingLinks.stream()
                    .anyMatch(link -> samePatientId(link.getFhirPatientId(), input.patientId()));
            if (alreadyLinkedToSamePatient) {
                return new AuthLinkPatientResponse("Hồ sơ bệnh nhân đã được liên kết.", userResponse(user));
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Tài khoản đã được liên kết với hồ sơ FHIR khác.");
        }

        JsonNode patient = getPatientForLinking(input.patientId());
        if (!patientMatches(patient, input)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Thông tin xác minh hồ sơ không khớp.");
        }
        if (userPatientLinkRepository.existsSelfLinkForOtherUser(input.patientId(), user.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Hồ sơ bệnh nhân này đã được liên kết với tài khoản khác.");
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
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Hồ sơ bệnh nhân này đã được liên kết.");
        }

        logLinkPatientSuccess(user, input.patientId());
        return new AuthLinkPatientResponse("Liên kết hồ sơ bệnh nhân thành công.", userResponse(user));
    }

    @Transactional
    public void logout() {
        User user = currentUserService.requireCurrentUser();
        user.incrementTokenVersion();
        userRepository.save(user);

        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("operation", "logout");
        auditLogRepository.save(user, null, "LOGOUT", "app_user", user.getId().toString(), metadata);
    }

    private AuthUserResponse userResponse(User user) {
        return AuthUserResponse.from(user, onboardingRequired(user));
    }

    private boolean onboardingRequired(User user) {
        return user.getRole() == UserRole.USER
                && userPatientLinkRepository.findPatientIdsForUser(user.getId()).isEmpty();
    }

    private RegistrationInput normalizeRegistration(AuthRegisterRequest request) {
        return new RegistrationInput(
                strip(request.displayName()),
                strip(request.username()).toLowerCase(),
                strip(request.email()).toLowerCase(),
                request.password(),
                request.passwordConfirmation()
        );
    }

    private void validateRegistration(RegistrationInput input) {
        if (input.displayName().length() < 2 || input.displayName().length() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tên hiển thị phải có từ 2 đến 100 ký tự.");
        }
        if (!input.username().matches("^[a-z0-9._-]{3,30}$")) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Tên đăng nhập phải có 3-30 ký tự và chỉ gồm chữ, số, dấu chấm, gạch dưới hoặc gạch ngang."
            );
        }
        if (!input.email().matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Email không hợp lệ.");
        }
        int passwordBytes = input.password().getBytes(StandardCharsets.UTF_8).length;
        if (passwordBytes < 8 || passwordBytes > 72) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mật khẩu phải có từ 8 đến 72 byte.");
        }
        if (!input.password().matches(".*\\p{L}.*") || !input.password().matches(".*\\d.*")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mật khẩu phải có ít nhất một chữ và một số.");
        }
        if (!input.password().equals(input.passwordConfirmation())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Xác nhận mật khẩu không khớp.");
        }
    }

    private LinkPatientInput normalizeLinkPatient(AuthLinkPatientRequest request) {
        return new LinkPatientInput(
                normalizePatientId(request.patientId()),
                strip(request.birthDate()),
                normalizePhone(request.phone())
        );
    }

    private void validateLinkPatient(LinkPatientInput input) {
        if (input.patientId() == null || input.patientId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mã bệnh nhân không hợp lệ.");
        }
        try {
            LocalDate.parse(input.birthDate());
        } catch (DateTimeParseException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ngày sinh phải có định dạng YYYY-MM-DD.");
        }
        if (input.phone() == null || input.phone().length() < 8 || input.phone().length() > 15) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Số điện thoại không hợp lệ.");
        }
    }

    private JsonNode getPatientForLinking(String patientId) {
        try {
            return chatbotServiceClient.getPatient(patientId);
        } catch (HttpClientErrorException.NotFound ex) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Thông tin xác minh hồ sơ không khớp.");
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().is4xxClientError()) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Thông tin xác minh hồ sơ không khớp.");
            }
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Không thể xác minh hồ sơ bệnh nhân từ FHIR service.");
        } catch (RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Không thể kết nối FHIR service để xác minh hồ sơ.");
        }
    }

    private boolean patientMatches(JsonNode patient, LinkPatientInput input) {
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

    private ResponseStatusException invalidCredentials(String credential) {
        logLoginFailure(null, credential, "INVALID_CREDENTIALS");
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Ten dang nhap/email hoac mat khau khong dung.");
    }

    private void logLoginSuccess(User user) {
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("operation", "login");
        metadata.put("result", "success");
        auditLogRepository.save(user, null, "LOGIN_SUCCESS", "app_user", user.getId().toString(), metadata);
    }

    private void logLoginFailure(User user, String credential, String reason) {
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("operation", "login");
        metadata.put("result", "failed");
        metadata.put("credential", credential);
        metadata.put("reason", reason);
        auditLogRepository.save(user, null, "LOGIN_FAILURE", "auth", credential, metadata);
    }

    private void logRegisterSuccess(User user) {
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("operation", "register");
        metadata.put("result", "success");
        metadata.put("role", user.getRole().name());
        auditLogRepository.save(user, null, "REGISTER_SUCCESS", "app_user", user.getId().toString(), metadata);
    }

    private void logLinkPatientSuccess(User user, String patientId) {
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("operation", "link_patient");
        metadata.put("result", "success");
        metadata.put("patient_id", patientId);
        auditLogRepository.save(user, null, "LINK_PATIENT_SUCCESS", "app_user_patient_link", patientId, metadata);
    }

    private record RegistrationInput(
            String displayName,
            String username,
            String email,
            String password,
            String passwordConfirmation
    ) {
    }

    private record LinkPatientInput(
            String patientId,
            String birthDate,
            String phone
    ) {
    }
}
