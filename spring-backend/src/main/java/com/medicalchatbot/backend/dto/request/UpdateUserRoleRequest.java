package com.medicalchatbot.backend.dto.request;

import com.medicalchatbot.backend.enums.UserRole;
import jakarta.validation.constraints.NotNull;

public record UpdateUserRoleRequest(
        @NotNull UserRole role
) {
}
