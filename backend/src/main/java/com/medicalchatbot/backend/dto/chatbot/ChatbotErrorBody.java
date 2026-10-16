package com.medicalchatbot.backend.dto.chatbot;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * Error body of chatbot-service ({@code 403/5xx}). {@code llmCalls} lists the calls that already
 * ran — and cost money — before the turn failed.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatbotErrorBody(String detail, List<ChatbotLlmCall> llmCalls) {

    public List<ChatbotLlmCall> llmCalls() {
        return llmCalls == null ? List.of() : llmCalls;
    }
}
