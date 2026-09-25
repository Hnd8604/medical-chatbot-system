package com.medicalchatbot.backend.service;

import java.util.UUID;

import com.medicalchatbot.backend.config.AuthenticatedUser;
import com.medicalchatbot.backend.entity.User;
import com.medicalchatbot.backend.enums.UserRole;
import com.medicalchatbot.backend.exception.AppException;
import com.medicalchatbot.backend.exception.ErrorCode;
import com.medicalchatbot.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CurrentUserService {

    private final UserRepository userRepository;

    public UUID getCurrentUserIdOrNull() {
        AuthenticatedUser principal = getPrincipal();
        return principal != null ? principal.id() : null;
    }

    public UUID requireCurrentUserId() {
        UUID userId = getCurrentUserIdOrNull();
        if (userId == null) {
            throw authenticationRequired("Bạn cần đăng nhập để tiếp tục.");
        }
        return userId;
    }

    public String getCurrentUsernameOrNull() {
        AuthenticatedUser principal = getPrincipal();
        return principal != null ? principal.username() : null;
    }

    public String getCurrentUsername() {
        String username = getCurrentUsernameOrNull();
        if (username == null) {
            throw authenticationRequired("Bạn cần đăng nhập để tiếp tục.");
        }
        return username;
    }

    public UserRole getCurrentUserRole() {
        AuthenticatedUser principal = getPrincipal();
        if (principal == null) {
            throw authenticationRequired("Bạn cần đăng nhập để tiếp tục.");
        }
        return principal.role();
    }

    public User requireCurrentUser() {
        UUID userId = requireCurrentUserId();
        return userRepository.findById(userId)
                .orElseThrow(() -> authenticationRequired("Người dùng không tồn tại."));
    }

    private AuthenticatedUser getPrincipal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof AuthenticatedUser authenticatedUser) {
            return authenticatedUser;
        }
        return null;
    }

    private static AppException authenticationRequired(String message) {
        return new AppException(ErrorCode.AUTHENTICATION_REQUIRED, message);
    }
}
