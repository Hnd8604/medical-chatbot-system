package com.medicalchatbot.backend.dto.response;

public record LoginResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresInSeconds,
        AuthUserResponse user
) {
}
