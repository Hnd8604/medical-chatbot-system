package com.medicalchatbot.backend.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.medicalchatbot.backend.entity.User;
import com.medicalchatbot.backend.enums.AuditAction;
import com.medicalchatbot.backend.enums.AuditResource;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.stereotype.Service;

/** Audit events of authentication and account management, named after what happened. */
@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class AuthAuditService {

    private static final String SUCCESS = "success";
    private static final String FAILED = "failed";

    AuditService auditService;

    public void loginSucceeded(User user) {
        recordOnAccount(user, AuditAction.LOGIN_SUCCESS, auditService.metadata("login", SUCCESS));
    }

    /** {@code user} is null when the credential matched no account. */
    public void loginFailed(User user, String credential, String reason) {
        ObjectNode metadata = auditService.metadata("login", FAILED)
                .put("credential", credential)
                .put("reason", reason);
        // The login is rejected by throwing, so this must not depend on the caller's transaction.
        auditService.recordIndependently(user, AuditAction.LOGIN_FAILURE, AuditResource.AUTH, credential, metadata);
    }

    public void tokenRefreshed(User user) {
        recordOnAccount(user, AuditAction.TOKEN_REFRESH, auditService.metadata("token_refresh", SUCCESS));
    }

    public void loggedOut(User user) {
        recordOnAccount(user, AuditAction.LOGOUT, auditService.metadata().put("operation", "logout"));
    }

    public void registered(User user) {
        ObjectNode metadata = auditService.metadata("register", SUCCESS).put("role", user.getRole().name());
        recordOnAccount(user, AuditAction.REGISTER_SUCCESS, metadata);
    }

    public void profileUpdated(User user, boolean emailChanged) {
        ObjectNode metadata = auditService.metadata("profile_update", SUCCESS).put("email_changed", emailChanged);
        recordOnAccount(user, AuditAction.PROFILE_UPDATE_SUCCESS, metadata);
    }

    public void passwordChanged(User user) {
        recordOnAccount(user, AuditAction.PASSWORD_CHANGE_SUCCESS, auditService.metadata("password_change", SUCCESS));
    }

    public void passwordChangeFailed(User user, String reason) {
        ObjectNode metadata = auditService.metadata("password_change", FAILED).put("reason", reason);
        auditService.recordIndependently(user, AuditAction.PASSWORD_CHANGE_FAILURE, AuditResource.APP_USER,
                user.getId().toString(), metadata);
    }

    public void passwordResetRequested(User user) {
        recordOnAccount(user, AuditAction.PASSWORD_RESET_REQUEST, auditService.metadata("password_reset", SUCCESS));
    }

    public void passwordReset(User user) {
        recordOnAccount(user, AuditAction.PASSWORD_RESET_SUCCESS, auditService.metadata("password_reset", SUCCESS));
    }

    public void patientLinked(User user, String patientId) {
        ObjectNode metadata = auditService.metadata("link_patient", SUCCESS).put("patient_id", patientId);
        auditService.record(user, AuditAction.LINK_PATIENT_SUCCESS, AuditResource.PATIENT_LINK, patientId, metadata);
    }

    private void recordOnAccount(User user, AuditAction action, ObjectNode metadata) {
        auditService.record(user, action, AuditResource.APP_USER, user.getId().toString(), metadata);
    }
}
