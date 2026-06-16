package com.medicalchatbot.backend.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

public record AuthLoginRequest(
        @JsonProperty("username_or_email")
        @NotBlank String usernameOrEmail,
        @NotBlank String password
) {
}
