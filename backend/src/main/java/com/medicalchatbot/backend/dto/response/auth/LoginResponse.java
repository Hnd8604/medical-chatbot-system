package com.medicalchatbot.backend.dto.response.auth;

public record LoginResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresInSeconds,
        AuthUserResponse user
) {
}
