package com.medicalchatbot.backend.domain.model;

/**
 * Conversation memory persisted with a chat session.
 *
 * <p>This is a domain value object rather than an API response model so the
 * persistence model stays independent from the web contract.</p>
 */
public record ChatSessionMemoryState(
        String activePatientId,
        String memorySummary,
        String lastIntent,
        String lastToolName,
        String lastResourceType,
        String lastResourceId
) {
    public static ChatSessionMemoryState empty() {
        return new ChatSessionMemoryState(null, null, null, null, null, null);
    }
}
