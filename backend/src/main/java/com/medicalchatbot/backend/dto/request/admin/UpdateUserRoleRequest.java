package com.medicalchatbot.backend.dto.request.admin;

import com.medicalchatbot.backend.enums.UserRole;
import jakarta.validation.constraints.NotNull;

public record UpdateUserRoleRequest(
        @NotNull(message = "Vui lòng chọn vai trò.")
        UserRole role
) {
}
