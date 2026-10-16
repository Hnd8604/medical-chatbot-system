package com.medicalchatbot.backend.dto.chatbot;

import java.util.List;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/** Session memory and recent messages sent with every chat turn. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ConversationContext(
        String memorySummary,
        String activePatientId,
        String lastIntent,
        String lastToolName,
        String lastResourceType,
        String lastResourceId,
        List<ContextMessage> recentMessages,
        /** Messages in the session before the current one; chatbot-service uses it to trigger rolling summaries. */
        int totalMessageCount
) {
}
