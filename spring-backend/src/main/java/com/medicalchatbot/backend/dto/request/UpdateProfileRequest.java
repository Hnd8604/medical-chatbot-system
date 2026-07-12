package com.medicalchatbot.backend.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

public record UpdateProfileRequest(
        @JsonProperty("display_name")
        @NotBlank String displayName,
        @NotBlank String email
) {
}
