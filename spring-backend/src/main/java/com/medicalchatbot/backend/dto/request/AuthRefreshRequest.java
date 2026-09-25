package com.medicalchatbot.backend.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AuthRefreshRequest(
        @JsonProperty("refresh_token")
        @NotBlank @Size(max = 512) String refreshToken
) {
}
