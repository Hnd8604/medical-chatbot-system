package com.medicalchatbot.backend.dto.response.chat;

import java.util.UUID;

public record FeedbackResponse(UUID id, UUID messageId, int rating, String comment) {
}
