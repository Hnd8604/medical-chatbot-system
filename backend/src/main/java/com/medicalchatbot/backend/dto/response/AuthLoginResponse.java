package com.medicalchatbot.backend.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AuthLoginResponse(
        @JsonProperty("access_token")
        String accessToken,
        @JsonProperty("refresh_token")
        String refreshToken,
        @JsonProperty("token_type")
        String tokenType,
        @JsonProperty("expires_in_seconds")
        long expiresInSeconds,
        AuthUserResponse user
) {
}
