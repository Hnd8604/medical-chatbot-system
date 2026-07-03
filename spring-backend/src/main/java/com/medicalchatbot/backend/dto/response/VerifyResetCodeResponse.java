package com.medicalchatbot.backend.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;

public record VerifyResetCodeResponse(
        @JsonProperty("reset_ticket")
        String resetTicket,
        String message
) {
}
