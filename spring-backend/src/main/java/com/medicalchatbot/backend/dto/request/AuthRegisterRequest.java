package com.medicalchatbot.backend.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AuthRegisterRequest(
        @JsonProperty("display_name")
        @NotBlank @Size(min = 2, max = 100) String displayName,
        @NotBlank @Pattern(regexp = "^[a-zA-Z0-9._-]{3,30}$") String username,
        @Email
        @NotBlank @Size(max = 255) String email,
        @NotBlank @Size(min = 8, max = 72) String password,
        @JsonProperty("password_confirmation")
        @NotBlank @Size(min = 8, max = 72) String passwordConfirmation
) {
}
