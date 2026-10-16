package com.medicalchatbot.backend.dto.response;

/** Result of an action whose only output is a message for the user. */
public record MessageResponse(String message) {
}
