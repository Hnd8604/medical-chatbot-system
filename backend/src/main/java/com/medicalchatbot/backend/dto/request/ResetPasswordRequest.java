package com.medicalchatbot.backend.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResetPasswordRequest(
        @JsonProperty("reset_ticket")
        @NotBlank @Size(max = 512) String resetTicket,
        @JsonProperty("new_password")
        @NotBlank @Size(min = 8, max = 72) String newPassword,
        @JsonProperty("password_confirmation")
        @NotBlank @Size(min = 8, max = 72) String passwordConfirmation
) {
}
