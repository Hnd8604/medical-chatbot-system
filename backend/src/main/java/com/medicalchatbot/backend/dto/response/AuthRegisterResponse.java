package com.medicalchatbot.backend.dto.response;

public record AuthRegisterResponse(
        String message,
        AuthUserResponse user
) {
}
