package com.medicalchatbot.backend.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateProfileRequest(
        @JsonProperty("display_name")
        @NotBlank @Size(min = 2, max = 100) String displayName,
        @NotBlank @Email @Size(max = 255) String email
) {
}
