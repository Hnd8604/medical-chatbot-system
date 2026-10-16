package com.medicalchatbot.backend.dto.response.auth;

import java.util.UUID;

import com.medicalchatbot.backend.enums.UserRole;
import com.medicalchatbot.backend.enums.UserStatus;

/** The signed-in account. {@code onboardingRequired}: a USER who has not linked a FHIR patient yet. */
public record AuthUserResponse(
        UUID id,
        String username,
        String email,
        String displayName,
        UserRole role,
        UserStatus status,
        boolean onboardingRequired
) {
}
