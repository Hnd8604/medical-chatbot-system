package com.medicalchatbot.backend.service;

import java.util.UUID;

import com.medicalchatbot.backend.entity.User;
import com.medicalchatbot.backend.exception.AppException;
import com.medicalchatbot.backend.repository.UserRepository;
import com.medicalchatbot.backend.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Access to the user of the current request. */
@Service
@RequiredArgsConstructor
public class CurrentUserService {

    private final UserRepository userRepository;

    public AuthenticatedUser requirePrincipal() {
        return AuthenticatedUser.current()
                .orElseThrow(() -> AppException.unauthorized("Bạn cần đăng nhập để tiếp tục."));
    }

    public UUID requireCurrentUserId() {
        return requirePrincipal().id();
    }

    public String requireCurrentUsername() {
        return requirePrincipal().username();
    }

    /** Loads the full user entity; use the principal accessors when only id/username are needed. */
    public User requireCurrentUser() {
        return userRepository.findById(requireCurrentUserId())
                .orElseThrow(() -> AppException.unauthorized("Người dùng không tồn tại."));
    }
}
