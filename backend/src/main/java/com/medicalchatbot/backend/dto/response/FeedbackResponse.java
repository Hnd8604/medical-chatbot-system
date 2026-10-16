package com.medicalchatbot.backend.dto.response;

import java.util.UUID;

public record FeedbackResponse(UUID id, UUID messageId, int rating, String comment) {
}
