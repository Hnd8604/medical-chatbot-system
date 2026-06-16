package com.medicalchatbot.backend.service;

import java.util.UUID;

import com.medicalchatbot.backend.config.AuthenticatedUser;
import com.medicalchatbot.backend.entity.User;
import com.medicalchatbot.backend.enums.UserRole;
import com.medicalchatbot.backend.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CurrentUserService {

    private final UserRepository userRepository;

    public CurrentUserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public UUID getCurrentUserIdOrNull() {
        AuthenticatedUser principal = getPrincipal();
        return principal != null ? principal.id() : null;
    }

    public UUID requireCurrentUserId() {
        UUID userId = getCurrentUserIdOrNull();
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Bạn cần đăng nhập để tiếp tục.");
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
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Bạn cần đăng nhập để tiếp tục.");
        }
        return username;
    }

    public UserRole getCurrentUserRole() {
        AuthenticatedUser principal = getPrincipal();
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Bạn cần đăng nhập để tiếp tục.");
        }
        return principal.role();
    }

    public User requireCurrentUser() {
        UUID userId = requireCurrentUserId();
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Người dùng không tồn tại."));
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
}
