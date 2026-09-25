package com.medicalchatbot.backend.dto.response;

public record AuthLinkPatientResponse(
        String message,
        AuthUserResponse user
) {
}
