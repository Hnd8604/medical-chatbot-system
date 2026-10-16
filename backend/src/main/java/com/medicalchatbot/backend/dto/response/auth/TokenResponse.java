package com.medicalchatbot.backend.dto.response.auth;

/** New token pair issued by {@code POST /auth/refresh}; the old refresh token is revoked. */
public record TokenResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresInSeconds
) {
}
