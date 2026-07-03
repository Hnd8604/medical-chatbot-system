package com.medicalchatbot.backend.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

public record ResetPasswordRequest(
        @JsonProperty("reset_ticket")
        @NotBlank String resetTicket,
        @JsonProperty("new_password")
        @NotBlank String newPassword,
        @JsonProperty("password_confirmation")
        @NotBlank String passwordConfirmation
) {
}
