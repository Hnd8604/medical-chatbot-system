package com.medicalchatbot.backend.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

public record AuthRefreshRequest(
        @JsonProperty("refresh_token")
        @NotBlank String refreshToken
) {
}
