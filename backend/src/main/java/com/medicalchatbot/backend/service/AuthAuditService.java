package com.medicalchatbot.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.medicalchatbot.backend.entity.User;
import com.medicalchatbot.backend.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Centralizes audit event construction for authentication and account actions.
 *
 * <p>Keeping this concern out of {@link AuthService} makes the authentication
 * workflow easier to read and gives audit persistence a single extension point.</p>
 */
@Service
@RequiredArgsConstructor
public class AuthAuditService {

    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    public void loginSucceeded(User user) {
        ObjectNode metadata = metadata("login", "success");
        auditLogRepository.save(user, null, "LOGIN_SUCCESS", "app_user", user.getId().toString(), metadata);
    }

    public void loginFailed(User user, String credential, String reason) {
        ObjectNode metadata = metadata("login", "failed");
        metadata.put("credential", credential);
        metadata.put("reason", reason);
        auditLogRepository.save(user, null, "LOGIN_FAILURE", "auth", credential, metadata);
    }

    public void tokenRefreshed(User user) {
        ObjectNode metadata = metadata("token_refresh", "success");
        auditLogRepository.save(user, null, "TOKEN_REFRESH", "app_user", user.getId().toString(), metadata);
    }

    public void registrationSucceeded(User user) {
        ObjectNode metadata = metadata("register", "success");
        metadata.put("role", user.getRole().name());
        auditLogRepository.save(user, null, "REGISTER_SUCCESS", "app_user", user.getId().toString(), metadata);
    }

    public void profileUpdated(User user, boolean emailChanged) {
        ObjectNode metadata = metadata("profile_update", "success");
        metadata.put("email_changed", emailChanged);
        auditLogRepository.save(user, null, "PROFILE_UPDATE_SUCCESS", "app_user", user.getId().toString(), metadata);
    }

    public void passwordChangeSucceeded(User user) {
        ObjectNode metadata = metadata("password_change", "success");
        auditLogRepository.save(user, null, "PASSWORD_CHANGE_SUCCESS", "app_user", user.getId().toString(), metadata);
    }

    public void passwordChangeFailed(User user, String reason) {
        ObjectNode metadata = metadata("password_change", "failed");
        metadata.put("reason", reason);
        auditLogRepository.save(user, null, "PASSWORD_CHANGE_FAILURE", "app_user", user.getId().toString(), metadata);
    }

    public void patientLinked(User user, String patientId) {
        ObjectNode metadata = metadata("link_patient", "success");
        metadata.put("patient_id", patientId);
        auditLogRepository.save(user, null, "LINK_PATIENT_SUCCESS", "app_user_patient_link", patientId, metadata);
    }

    public void loggedOut(User user) {
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("operation", "logout");
        auditLogRepository.save(user, null, "LOGOUT", "app_user", user.getId().toString(), metadata);
    }

    private ObjectNode metadata(String operation, String result) {
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("operation", operation);
        metadata.put("result", result);
        return metadata;
    }
}
