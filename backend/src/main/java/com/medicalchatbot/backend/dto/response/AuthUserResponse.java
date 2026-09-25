package com.medicalchatbot.backend.dto.response;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.medicalchatbot.backend.entity.User;
import com.medicalchatbot.backend.enums.UserRole;
import com.medicalchatbot.backend.enums.UserStatus;

public record AuthUserResponse(
        UUID id,
        String username,
        String email,
        @JsonProperty("display_name")
        String displayName,
        UserRole role,
        UserStatus status,
        @JsonProperty("onboarding_required")
        boolean onboardingRequired
) {
    public static AuthUserResponse from(User user) {
        return from(user, false);
    }

    public static AuthUserResponse from(User user, boolean onboardingRequired) {
        return new AuthUserResponse(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getDisplayName(),
                user.getRole(),
                user.getStatus(),
                onboardingRequired
        );
    }
}
