package com.medicalchatbot.backend.dto.request;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChatRequest(
        @JsonProperty("session_id")
        UUID sessionId,

        @JsonProperty("patient_id")
        @Size(max = 100) String patientId,

        @NotBlank
        @Size(max = 8000)
        String message
) {
}
