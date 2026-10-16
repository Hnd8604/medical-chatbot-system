package com.medicalchatbot.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * Conversation memory stored on a chat session and sent back to chatbot-service on the next turn,
 * so follow-up questions ("còn thuốc thì sao?") resolve against the same patient and resource.
 */
@Embeddable
public record ChatMemory(
        @Column(name = "active_patient_id", length = 100)
        String activePatientId,

        @Column(name = "memory_summary", columnDefinition = "text")
        String summary,

        @Column(name = "last_intent", length = 100)
        String lastIntent,

        @Column(name = "last_tool_name", length = 100)
        String lastToolName,

        @Column(name = "last_resource_type", length = 100)
        String lastResourceType,

        @Column(name = "last_resource_id", length = 100)
        String lastResourceId
) {

    public static final ChatMemory EMPTY = new ChatMemory(null, null, null, null, null, null);

    /** Fresh memory that only remembers which patient the conversation is about. */
    public static ChatMemory forPatient(String patientId) {
        return new ChatMemory(patientId, null, null, null, null, null);
    }
}
