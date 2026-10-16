package com.medicalchatbot.backend.dto.chatbot;

import java.util.List;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * Body of {@code POST /chat} on chatbot-service. Spring has already authenticated the user and
 * resolved which patients they may see; chatbot-service enforces the scope it receives here.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ChatbotChatRequest(
        String userId,
        String userRole,
        String sessionId,
        String message,
        String patientId,
        List<String> allowedPatientIds,
        /** {@code SELF} (USER accounts) or {@code STAFF}. */
        String patientScope,
        ConversationContext conversationContext,
        /** Share of today's quota already used (0..1+); lets chatbot-service pick a cheaper model. */
        double quotaUsedRatio
) {
}
