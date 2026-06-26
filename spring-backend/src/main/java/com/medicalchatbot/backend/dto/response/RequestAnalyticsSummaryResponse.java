package com.medicalchatbot.backend.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;

public record RequestAnalyticsSummaryResponse(
        String from,
        String to,
        @JsonProperty("request_count")
        long requestCount
) {
}
