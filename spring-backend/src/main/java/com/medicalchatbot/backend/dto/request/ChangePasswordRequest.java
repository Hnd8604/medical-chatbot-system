package com.medicalchatbot.backend.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

public record ChangePasswordRequest(
        @JsonProperty("current_password")
        @NotBlank String currentPassword,
        @JsonProperty("new_password")
        @NotBlank String newPassword,
        @JsonProperty("password_confirmation")
        @NotBlank String passwordConfirmation
) {
}
