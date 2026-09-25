package com.medicalchatbot.backend.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AuthLoginRequest(
        @JsonProperty("username_or_email")
        @NotBlank @Size(max = 255) String usernameOrEmail,
        @NotBlank @Size(max = 72) String password
) {
}
