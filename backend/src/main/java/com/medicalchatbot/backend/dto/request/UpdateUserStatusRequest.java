package com.medicalchatbot.backend.dto.request;

import com.medicalchatbot.backend.enums.UserStatus;
import jakarta.validation.constraints.NotNull;

public record UpdateUserStatusRequest(
        @NotNull UserStatus status
) {
}
