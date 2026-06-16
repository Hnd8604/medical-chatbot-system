package com.medicalchatbot.backend.config;

import java.security.Principal;
import java.util.UUID;

import com.medicalchatbot.backend.enums.UserRole;
import com.medicalchatbot.backend.enums.UserStatus;

public record AuthenticatedUser(
        UUID id,
        String username,
        UserRole role,
        UserStatus status,
        int tokenVersion
) implements Principal {

    @Override
    public String getName() {
        return username;
    }
}
