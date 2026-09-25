package com.medicalchatbot.backend.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChangePasswordRequest(
        @JsonProperty("current_password")
        @NotBlank @Size(max = 72) String currentPassword,
        @JsonProperty("new_password")
        @NotBlank @Size(min = 8, max = 72) String newPassword,
        @JsonProperty("password_confirmation")
        @NotBlank @Size(min = 8, max = 72) String passwordConfirmation
) {
}
