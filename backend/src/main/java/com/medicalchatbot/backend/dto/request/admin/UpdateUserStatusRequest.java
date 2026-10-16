package com.medicalchatbot.backend.dto.request.admin;

import com.medicalchatbot.backend.enums.UserStatus;
import jakarta.validation.constraints.NotNull;

public record UpdateUserStatusRequest(
        @NotNull(message = "Vui lòng chọn trạng thái.")
        UserStatus status
) {
}
