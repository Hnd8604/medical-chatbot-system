package com.medicalchatbot.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.medicalchatbot.backend.dto.request.AuthLoginRequest;
import com.medicalchatbot.backend.dto.response.AuthLoginResponse;
import com.medicalchatbot.backend.dto.response.AuthUserResponse;
import com.medicalchatbot.backend.entity.User;
import com.medicalchatbot.backend.enums.UserStatus;
import com.medicalchatbot.backend.repository.AuditLogRepository;
import com.medicalchatbot.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService jwtTokenService;
    private final CurrentUserService currentUserService;
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
                AuthUserResponse.from(user)
        );
    }

    @Transactional(readOnly = true)
    public AuthUserResponse currentUser() {
        return AuthUserResponse.from(currentUserService.requireCurrentUser());
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
}
