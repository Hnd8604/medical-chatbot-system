package com.medicalchatbot.backend.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record AuthRegisterRequest(
        @JsonProperty("display_name")
        @NotBlank String displayName,
        @NotBlank String username,
        @Email
        @NotBlank String email,
        @NotBlank String password,
        @JsonProperty("password_confirmation")
        @NotBlank String passwordConfirmation
) {
}
