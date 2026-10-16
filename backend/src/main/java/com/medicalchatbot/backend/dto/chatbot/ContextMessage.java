package com.medicalchatbot.backend.dto.chatbot;

/** A past message of the conversation; {@code role} is {@code user} or {@code assistant}. */
public record ContextMessage(String role, String content) {
}
